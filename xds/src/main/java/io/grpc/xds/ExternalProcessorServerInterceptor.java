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
import static io.grpc.xds.internal.extproc.ExternalProcessorUtil.applyHeaderMutations;
import static io.grpc.xds.internal.extproc.ExternalProcessorUtil.collectAttributes;
import static io.grpc.xds.internal.extproc.ExternalProcessorUtil.markDataPlaneCallClosed;
import static io.grpc.xds.internal.extproc.ExternalProcessorUtil.markExtProcStreamCompleted;
import static io.grpc.xds.internal.extproc.ExternalProcessorUtil.markExtProcStreamFailed;
import static io.grpc.xds.internal.extproc.ExternalProcessorUtil.outboundStreamToByteString;
import static io.grpc.xds.internal.extproc.ExternalProcessorUtil.toHeaderMap;

import com.google.common.annotations.VisibleForTesting;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import com.google.common.util.concurrent.MoreExecutors;
import com.google.protobuf.ByteString;
import com.google.protobuf.Struct;
import io.envoyproxy.envoy.extensions.filters.http.ext_proc.v3.ProcessingMode;
import io.envoyproxy.envoy.service.ext_proc.v3.BodyMutation;
import io.envoyproxy.envoy.service.ext_proc.v3.BodyResponse;
import io.envoyproxy.envoy.service.ext_proc.v3.CommonResponse;
import io.envoyproxy.envoy.service.ext_proc.v3.ExternalProcessorGrpc;
import io.envoyproxy.envoy.service.ext_proc.v3.HttpBody;
import io.envoyproxy.envoy.service.ext_proc.v3.HttpHeaders;
import io.envoyproxy.envoy.service.ext_proc.v3.HttpTrailers;
import io.envoyproxy.envoy.service.ext_proc.v3.ImmediateResponse;
import io.envoyproxy.envoy.service.ext_proc.v3.ProcessingRequest;
import io.envoyproxy.envoy.service.ext_proc.v3.ProcessingResponse;
import io.envoyproxy.envoy.service.ext_proc.v3.ProtocolConfiguration;
import io.envoyproxy.envoy.service.ext_proc.v3.StreamedBodyResponse;
import io.grpc.Context;
import io.grpc.DoubleHistogramMetricInstrument;
import io.grpc.ForwardingServerCall.SimpleForwardingServerCall;
import io.grpc.ManagedChannel;
import io.grpc.Metadata;
import io.grpc.MethodDescriptor;
import io.grpc.MetricInstrumentRegistry;
import io.grpc.MetricRecorder;
import io.grpc.ServerCall;
import io.grpc.ServerCallHandler;
import io.grpc.ServerInterceptor;
import io.grpc.Status;
import io.grpc.StatusRuntimeException;
import io.grpc.internal.GrpcUtil;
import io.grpc.internal.SharedResourceHolder;
import io.grpc.stub.ClientCallStreamObserver;
import io.grpc.stub.ClientResponseObserver;
import io.grpc.stub.MetadataUtils;
import io.grpc.xds.ExternalProcessorFilter.ExternalProcessorFilterConfig;
import io.grpc.xds.Filter.FilterContext;
import io.grpc.xds.internal.extproc.DataPlaneCallState;
import io.grpc.xds.internal.extproc.EventType;
import io.grpc.xds.internal.extproc.ExtProcStreamState;
import io.grpc.xds.internal.extproc.KnownLengthInputStream;
import io.grpc.xds.internal.grpcservice.CachedChannelManager;
import io.grpc.xds.internal.grpcservice.HeaderValue;
import io.grpc.xds.internal.headermutations.HeaderMutationDisallowedException;
import io.grpc.xds.internal.headermutations.HeaderMutationFilter;
import io.grpc.xds.internal.headermutations.HeaderMutationRulesConfig;
import io.grpc.xds.internal.headermutations.HeaderMutator;
import java.io.IOException;
import java.io.InputStream;
import java.util.List;
import java.util.ArrayList;
import java.util.Optional;
import java.util.Queue;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import javax.annotation.concurrent.GuardedBy;

/**
 * Server-side interceptor for external processing filter.
 */
final class ExternalProcessorServerInterceptor implements ServerInterceptor {

  @VisibleForTesting
  static DoubleHistogramMetricInstrument clientHeadersDuration;
  @VisibleForTesting
  static DoubleHistogramMetricInstrument clientHalfCloseDuration;
  @VisibleForTesting
  static DoubleHistogramMetricInstrument serverHeadersDuration;
  @VisibleForTesting
  static DoubleHistogramMetricInstrument serverTrailersDuration;

  // Copied from io.grpc.opentelemetry.internal.OpenTelemetryConstants.LATENCY_BUCKETS
  private static final List<Double> LATENCY_BUCKETS = ImmutableList.of(
      0d,     0.00001d, 0.00005d, 0.0001d, 0.0003d, 0.0006d, 0.0008d, 0.001d, 0.002d,
      0.003d, 0.004d,   0.005d,   0.006d,  0.008d,  0.01d,   0.013d,  0.016d, 0.02d,
      0.025d, 0.03d,    0.04d,    0.05d,   0.065d,  0.08d,   0.1d,    0.13d,  0.16d,
      0.2d,   0.25d,    0.3d,     0.4d,    0.5d,    0.65d,   0.8d,    1d,     2d,
      5d,     10d,      20d,      50d,     100d);

  static {
     initMetricInstruments();
  }

  public static synchronized void initMetricInstruments() {
    if (GrpcUtil.getFlag("GRPC_EXPERIMENTAL_XDS_EXT_PROC_ON_SERVER", false)) {
      if (clientHeadersDuration == null) {
        MetricInstrumentRegistry registry = MetricInstrumentRegistry.getDefaultRegistry();

        clientHeadersDuration = registry.registerDoubleHistogram(
            "grpc.server_ext_proc.client_headers_duration",
            "Time between when the ext_proc filter sees the client's headers and when "
                + "it allows those headers to continue on to the next filter",
            "s",
            LATENCY_BUCKETS,
            ImmutableList.of(),
            ImmutableList.of(),
            true);

        clientHalfCloseDuration = registry.registerDoubleHistogram(
            "grpc.server_ext_proc.client_half_close_duration",
            "Time between when the ext_proc filter sees the client's half-close and when "
                + "it allows that half-close to continue on to the next filter",
            "s",
            LATENCY_BUCKETS,
            ImmutableList.of(),
            ImmutableList.of(),
            true);

        serverHeadersDuration = registry.registerDoubleHistogram(
            "grpc.server_ext_proc.server_headers_duration",
            "Time between when the ext_proc filter sees the server's headers and when "
                + "it allows those headers to continue on to the next filter",
            "s",
            LATENCY_BUCKETS,
            ImmutableList.of(),
            ImmutableList.of(),
            true);

        serverTrailersDuration = registry.registerDoubleHistogram(
            "grpc.server_ext_proc.server_trailers_duration",
            "Time between when the ext_proc filter sees the server's trailers and when "
                + "it allows those trailers to continue on to the next filter",
            "s",
            LATENCY_BUCKETS,
            ImmutableList.of(),
            ImmutableList.of(),
            true);
      }
    }
  }

  private final ExternalProcessorFilterConfig filterConfig;
  private final MetricRecorder metricsRecorder;
  private final ManagedChannel extProcChannel;

  ExternalProcessorServerInterceptor(
      ExternalProcessorFilterConfig filterConfig,
      CachedChannelManager cachedChannelManager,
      FilterContext context) {
    this.filterConfig = checkNotNull(filterConfig, "filterConfig");
    checkNotNull(cachedChannelManager, "cachedChannelManager");
    this.metricsRecorder = checkNotNull(context.metricsRecorder(), "metricsRecorder");
    this.extProcChannel = cachedChannelManager.getChannel(filterConfig.getGrpcServiceConfig());
  }

  ExternalProcessorFilterConfig getFilterConfig() {
    return filterConfig;
  }

  @Override
  @SuppressWarnings("unchecked")
  public <ReqT, RespT> ServerCall.Listener<ReqT> interceptCall(
      ServerCall<ReqT, RespT> call, Metadata headers, ServerCallHandler<ReqT, RespT> next) {
    ServerCall<InputStream, InputStream> rawCall = (ServerCall<InputStream, InputStream>) call;
    ServerCallHandler<InputStream, InputStream> rawNext =
        (ServerCallHandler<InputStream, InputStream>) next;

    ExternalProcessorGrpc.ExternalProcessorStub extProcStub = ExternalProcessorGrpc.newStub(
        extProcChannel)
        .withExecutor(MoreExecutors.directExecutor());

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
          MetadataUtils.newAttachHeadersInterceptor(extraHeaders));
    }

    Context callContext = Context.current();

    DataPlaneServerCall dataPlaneServerCall = new DataPlaneServerCall(
        rawCall, extProcStub, filterConfig, filterConfig.getMutationRulesConfig(),
        SharedResourceHolder.get(GrpcUtil.TIMER_SERVICE), call.getMethodDescriptor(),
        metricsRecorder, call.getAuthority(), rawNext, headers, callContext);

    dataPlaneServerCall.start();

    return (ServerCall.Listener<ReqT>) dataPlaneServerCall.getListener();
  }


  static class DataPlaneServerCall
      extends SimpleForwardingServerCall<InputStream, InputStream> {

    private final ServerCall<InputStream, InputStream> rawCall;
    private final ExternalProcessorGrpc.ExternalProcessorStub extProcStub;
    private final ExternalProcessorFilterConfig config;
    private final ScheduledExecutorService scheduler;
    final Object streamLock = new Object();
    private final Queue<EventType> expectedResponses = new ConcurrentLinkedQueue<>();
    private volatile ClientCallStreamObserver<ProcessingRequest> extProcClientCallRequestObserver;
    private final Queue<InputStream> pendingDrainingOutgoingMessages = new ConcurrentLinkedQueue<>();
    private final Queue<InputStream> savedOutgoingMessagesAwaitingHeaderMutation = new ConcurrentLinkedQueue<>();
    private volatile DataPlaneServerListener wrappedListener;
    private final HeaderMutationFilter mutationFilter;
    private final HeaderMutator mutator = HeaderMutator.create();
    private final AtomicInteger pendingRequests = new AtomicInteger(0);
    private final ProcessingMode currentProcessingMode;
    private final MethodDescriptor<?, ?> method;
    private final MetricRecorder metricsRecorder;
    private final String authority;
    private final ServerCallHandler<InputStream, InputStream> rawNext;
    private final Context callContext;
    private volatile Metadata requestHeaders;

    @GuardedBy("streamLock")
    private volatile Metadata savedResponseHeaders;
    @GuardedBy("streamLock")
    private volatile Status savedStatus;
    @GuardedBy("streamLock")
    private volatile Metadata savedTrailers;

    @GuardedBy("streamLock")
    private boolean protocolConfigSent = false;
    @GuardedBy("streamLock")
    private ImmutableMap<String, Struct> collectedAttributes;
    @GuardedBy("streamLock")
    private boolean requestAttributesSent = false;

    // Default initial window size
    private static final long DEFAULT_INITIAL_WINDOW_SIZE = 65536;

    // Outbound (sending) windows
    @GuardedBy("streamLock")
    private long downstreamToSidestreamWindow = DEFAULT_INITIAL_WINDOW_SIZE;
    @GuardedBy("streamLock")
    private long upstreamToSidestreamWindow = DEFAULT_INITIAL_WINDOW_SIZE;

    // Inbound (receiving) windows
    @GuardedBy("streamLock")
    private long sidestreamToUpstreamWindow = DEFAULT_INITIAL_WINDOW_SIZE;
    @GuardedBy("streamLock")
    private long sidestreamToDownstreamWindow = DEFAULT_INITIAL_WINDOW_SIZE;

    // Path 1 flow control: Pending/buffered request body messages from client to send to
    // ext_proc
    @GuardedBy("streamLock")
    final Queue<ByteString> pendingRequestBodyMessages = new ConcurrentLinkedQueue<>();

    // Path 2 flow control: Buffered mutated request bodies from ext_proc to deliver to App
    @GuardedBy("streamLock")
    final Queue<StreamedBodyResponse> pendingMutatedRequestBodies = new ConcurrentLinkedQueue<>();
    @GuardedBy("streamLock")
    private final AtomicInteger pendingAppRequests = new AtomicInteger(0);
    @GuardedBy("streamLock")
    private final AtomicInteger pendingTransportRequests = new AtomicInteger(0);

    // Path 3 flow control: Pending/buffered response body messages from App to send to ext_proc
    @GuardedBy("streamLock")
    private final Queue<ByteString> pendingResponseBodyMessages = new ConcurrentLinkedQueue<>();
    @GuardedBy("streamLock")
    private int outstandingResponseBodyRequests = 0;
    @GuardedBy("streamLock")
    private final AtomicBoolean pendingClose = new AtomicBoolean(false);

    // Path 4 flow control: Buffered mutated response bodies from ext_proc to send to client transport
    @GuardedBy("streamLock")
    private final Queue<ByteString> pendingDownstreamBodyMessages = new ConcurrentLinkedQueue<>();

    // Threshold to trigger standalone client window updates
    private static final long WINDOW_UPDATE_THRESHOLD = DEFAULT_INITIAL_WINDOW_SIZE / 2;

    // Accumulated client window updates to send to ext_proc
    @GuardedBy("streamLock")
    private long accumulatedWindowUpdateSidestreamToUpstream = 0;
    @GuardedBy("streamLock")
    private long accumulatedWindowUpdateSidestreamToDownstream = 0;

    // Flag to track if FlowControlInit was sent in the initial message
    @GuardedBy("streamLock")
    private boolean flowControlInitSent = false;

    private long clientHeadersStartNanos;
    private long clientHalfCloseStartNanos;
    private long serverHeadersStartNanos;
    private long serverTrailersStartNanos;

    final AtomicReference<DataPlaneCallState> dataPlaneCallState =
        new AtomicReference<>(DataPlaneCallState.IDLE);
    final AtomicReference<ExtProcStreamState> extProcStreamState =
        new AtomicReference<>(ExtProcStreamState.ACTIVE);
    final AtomicBoolean passThroughMode = new AtomicBoolean(false);
    final AtomicBoolean requestSideClosed = new AtomicBoolean(false);
    final AtomicBoolean dataPlaneCallClosed = new AtomicBoolean(false);
    final AtomicBoolean bodyMessageSentToExtProc = new AtomicBoolean(false);

    final AtomicBoolean isProcessingTrailers = new AtomicBoolean(false);
    final AtomicBoolean responseHeadersSent = new AtomicBoolean(false);
    final AtomicBoolean trailersOnly = new AtomicBoolean(false);
    final AtomicBoolean terminationTriggered = new AtomicBoolean(false);
    private final AtomicBoolean closeCalled = new AtomicBoolean(false);



    protected DataPlaneServerCall(
        ServerCall<InputStream, InputStream> rawCall,
        ExternalProcessorGrpc.ExternalProcessorStub extProcStub,
        ExternalProcessorFilterConfig config,
        Optional<HeaderMutationRulesConfig> mutationRulesConfig,
        ScheduledExecutorService scheduler,
        MethodDescriptor<?, ?> method,
        MetricRecorder metricsRecorder,
        String authority,
        ServerCallHandler<InputStream, InputStream> rawNext,
        Metadata requestHeaders,
        Context callContext) {
      super(rawCall);
      this.rawCall = rawCall;
      this.extProcStub = extProcStub.withExecutor(MoreExecutors.directExecutor());
      this.config = config;
      this.currentProcessingMode = config.getExternalProcessor().getProcessingMode();
      this.mutationFilter = new HeaderMutationFilter(mutationRulesConfig);
      this.scheduler = scheduler;
      this.method = method;
      this.metricsRecorder = checkNotNull(metricsRecorder, "metricsRecorder");
      this.authority = authority;
      this.rawNext = rawNext;
      this.requestHeaders = requestHeaders;
      this.callContext = callContext;
      this.wrappedListener = new DataPlaneServerListener(this);
    }

    DataPlaneServerListener getListener() {
      return wrappedListener;
    }

    boolean isExtProcStreamCompleted() {
      return extProcStreamState.get().isCompleted();
    }

    boolean isExtProcStreamFailed() {
      return extProcStreamState.get().isFailed();
    }

    boolean isExtProcStreamDraining() {
      return extProcStreamState.get().isDraining();
    }

    private boolean isSimpleServerCall(Class<?> clazz) {
      if (clazz == null) {
        return false;
      }
      if (clazz.getName().contains("SimpleServerCall")) {
        return true;
      }
      return isSimpleServerCall(clazz.getSuperclass());
    }

    @Override
    public void triggerEvent(Object event) {
      if (isSimpleServerCall(rawCall.getClass())) {
        wrappedListener.onEvent(event);
      } else {
        super.triggerEvent(event);
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
      Context previous = callContext.attach();
      ServerCall.Listener<InputStream> appListener;
      try {
        appListener = rawNext.startCall(this, requestHeaders);
      } finally {
        callContext.detach(previous);
      }
      wrappedListener.setDelegate(appListener);
      drainPendingRequests();
      wrappedListener.onReadyNotify();
      if (wrappedListener.halfCloseDeferred) {
        wrappedListener.handleDeferredHalfClose();
      }
    }

    private void recordDuration(DoubleHistogramMetricInstrument instrument, long durationNanos) {
      if (instrument != null) {
        double durationSecs = (double) durationNanos / 1_000_000_000.0;
        metricsRecorder.recordDoubleHistogram(
            instrument,
            durationSecs,
            ImmutableList.of(),
            ImmutableList.of());
      }
    }

    private boolean validateCompressionSupport(BodyResponse bodyResponse) {
      if (bodyResponse.hasResponse() && bodyResponse.getResponse().hasBodyMutation()) {
        BodyMutation mutation = bodyResponse.getResponse().getBodyMutation();
        if (mutation.hasStreamedResponse()
            && mutation.getStreamedResponse().getGrpcMessageCompressed()) {
          StatusRuntimeException ex = Status.UNAVAILABLE
              .withDescription("gRPC message compression not supported in ext_proc")
              .asRuntimeException();
          synchronized (streamLock) {
            if (!isExtProcStreamCompleted() && extProcClientCallRequestObserver != null) {
              extProcClientCallRequestObserver.onError(ex);
            }
          }
          activateCall();
          markExtProcStreamFailed(extProcStreamState);
          rawCall.close(
              Status.UNAVAILABLE.withDescription(
                  "gRPC message compression not supported in ext_proc"),
              new Metadata());
          closeExtProcStream();
          return false;
        }
      }
      return true;
    }



    void start() {
      clientHeadersStartNanos = System.nanoTime();
      synchronized (streamLock) {
        this.collectedAttributes = collectAttributes(
            config.getRequestAttributes(), method, authority, requestHeaders);
      }

      extProcStub.process(new ClientResponseObserver<ProcessingRequest, ProcessingResponse>() {
        @Override
        public void beforeStart(ClientCallStreamObserver<ProcessingRequest> requestStream) {
          synchronized (streamLock) {
            extProcClientCallRequestObserver = requestStream;
          }
          requestStream.setOnReadyHandler(() -> DataPlaneServerCall.this.triggerEvent(new ExtProcStreamReadyEvent()));
        }

        @Override
        public void onNext(ProcessingResponse response) {
          System.out.println("JETS_LOG: onNext response: " + response);
          DataPlaneServerCall.this.triggerEvent(new ExtProcResponseEvent(response));
        }

        @Override
        public void onError(Throwable t) {
          DataPlaneServerCall.this.triggerEvent(new ExtProcErrorEvent(t));
        }

        @Override
        public void onCompleted() {
          DataPlaneServerCall.this.triggerEvent(new ExtProcCompletedEvent());
        }
      });

      boolean sendRequestHeaders =
          currentProcessingMode.getRequestHeaderMode() == ProcessingMode.HeaderSendMode.SEND
          || currentProcessingMode.getRequestHeaderMode()
              == ProcessingMode.HeaderSendMode.DEFAULT;

      if (sendRequestHeaders) {
        sendToExtProc(ProcessingRequest.newBuilder()
            .setRequestHeaders(HttpHeaders.newBuilder()
                .setHeaders(toHeaderMap(requestHeaders, config.getForwardRulesConfig()))
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
        if (isExtProcStreamCompleted()) {
          return;
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
        } else if (!flowControlInitSent) {
          requestToSend = ProcessingRequest.newBuilder(requestToSend)
              .setFlowControlInit(ProcessingRequest.FlowControlInit.newBuilder()
                  .setInitialWindowDownstreamToSidestream(DEFAULT_INITIAL_WINDOW_SIZE)
                  .setInitialWindowSidestreamToUpstream(DEFAULT_INITIAL_WINDOW_SIZE)
                  .setInitialWindowUpstreamToSidestreama(DEFAULT_INITIAL_WINDOW_SIZE)
                  .setInitialWindowSidestreamToDownstream(DEFAULT_INITIAL_WINDOW_SIZE)
                  .build())
              .build();
          flowControlInitSent = true;
        }

        if (requestToSend.hasRequestHeaders()) {
          expectedResponses.add(EventType.REQUEST_HEADERS);
        } else if (requestToSend.hasResponseHeaders()) {
          expectedResponses.add(EventType.RESPONSE_HEADERS);
        } else if (requestToSend.hasResponseTrailers()) {
          expectedResponses.add(EventType.RESPONSE_TRAILERS);
        }
        if (requestToSend.hasResponseBody()) {
          outstandingResponseBodyRequests++;
        }

        extProcClientCallRequestObserver.onNext(requestToSend);
      }
    }

    @GuardedBy("streamLock")
    private void mergeAccumulatedWindowUpdates(ProcessingRequest.Builder requestBuilder) {
      long incrementUpstream = accumulatedWindowUpdateSidestreamToUpstream;
      long incrementDownstream = accumulatedWindowUpdateSidestreamToDownstream;

      if (incrementUpstream > 0 || incrementDownstream > 0) {
        requestBuilder.setClientWindowUpdate(
            ProcessingRequest.ClientWindowUpdate.newBuilder()
                .setWindowIncrementSidestreamToUpstream(incrementUpstream)
                .setWindowIncrementSidestreamToDownstream(incrementDownstream)
                .build());
        accumulatedWindowUpdateSidestreamToUpstream -= incrementUpstream;
        accumulatedWindowUpdateSidestreamToDownstream -= incrementDownstream;
        sidestreamToUpstreamWindow += incrementUpstream;
        sidestreamToDownstreamWindow += incrementDownstream;
      }
    }

    private void trySendAccumulatedWindowUpdates() {
      synchronized (streamLock) {
        if (isExtProcStreamCompleted()) {
          return;
        }
        if (accumulatedWindowUpdateSidestreamToUpstream >= WINDOW_UPDATE_THRESHOLD
            || accumulatedWindowUpdateSidestreamToDownstream >= WINDOW_UPDATE_THRESHOLD
            || sidestreamToUpstreamWindow < 0
            || sidestreamToDownstreamWindow < 0) {
          ProcessingRequest.Builder builder = ProcessingRequest.newBuilder();
          mergeAccumulatedWindowUpdates(builder);
          if (builder.hasClientWindowUpdate()) {
            sendToExtProc(builder.build());
          }
        }
      }
    }

    void onExtProcStreamReady() {
      drainPendingRequests();
      wrappedListener.onReadyNotify();
    }

    private void drainPendingRequests() {
      if (config.getObservabilityMode()
          || currentProcessingMode.getRequestBodyMode() != ProcessingMode.BodySendMode.GRPC
          || isExtProcStreamCompleted()) {
        int toRequest = pendingRequests.getAndSet(0);
        if (toRequest > 0) {
          super.request(toRequest);
        }
        return;
      }

      // Normal mode flow control: pull 1 message at a time
      while (true) {
        boolean pull = false;
        synchronized (streamLock) {
          if (isSidecarReady()
              && downstreamToSidestreamWindow > 0
              && pendingTransportRequests.get() > 0) {
            pull = true;
            pendingTransportRequests.decrementAndGet();
          }
        }
        if (pull) {
          super.request(1);
        } else {
          break;
        }
      }
    }

    private void closeExtProcStream() {
      synchronized (streamLock) {
        if (markExtProcStreamCompleted(extProcStreamState)) {
          if (extProcClientCallRequestObserver != null) {
            extProcClientCallRequestObserver.onCompleted();
          }
        }
        expectedResponses.clear();
      }
      proceedWithClose();
    }

    private void cancelExtProcStream(Throwable t) {
      if (markExtProcStreamFailed(extProcStreamState)) {
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
        expectedResponses.clear();
        proceedWithClose();
      }
    }

    private void internalOnError(Throwable t) {

      if (markExtProcStreamFailed(extProcStreamState)) {
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
        expectedResponses.clear();
        if (config.getObservabilityMode()
            || (config.getFailureModeAllow() && !bodyMessageSentToExtProc.get())) {
          handleFailOpen();
        } else {
          proceedWithClose(
              Status.INTERNAL.withDescription("External processor stream failed").withCause(t),
              new Metadata());
        }
      }
    }

    void handleExtProcResponse(ProcessingResponse response) {
      try {
        System.out.println("JETS_LOG: handleExtProcResponse: hasResponseBody=" + response.hasResponseBody()
            + ", expected=" + expectedResponses.peek()
            + ", expectedResponses=" + expectedResponses);
        if (config.getObservabilityMode()) {
          return;
        }

        if (response.hasServerWindowUpdate()) {
          ProcessingResponse.ServerWindowUpdate update = response.getServerWindowUpdate();
          synchronized (streamLock) {
            downstreamToSidestreamWindow += update.getWindowIncrementDownstreamToSidestream();
            upstreamToSidestreamWindow += update.getWindowIncrementUpstreamToSidestream();
          }
          if (wrappedListener != null) {
            wrappedListener.drainPendingRequestBodyMessages();
          }
          drainPendingResponseBodyMessages();
          drainPendingRequests();
        }

        if (response.hasImmediateResponse()) {
          if (config.getDisableImmediateResponse()) {
            internalOnError(Status.UNAVAILABLE
                .withDescription(
                    "Immediate response is disabled but received from external processor")
                .asRuntimeException());
            return;
          }
          handleImmediateResponse(response.getImmediateResponse());
          return;
        }

        EventType expected = expectedResponses.peek();
        EventType received = null;
        if (response.hasRequestHeaders()) {
          received = EventType.REQUEST_HEADERS;
        } else if (response.hasResponseHeaders()) {
          received = EventType.RESPONSE_HEADERS;
        } else if (response.hasResponseTrailers()) {
          received = EventType.RESPONSE_TRAILERS;
        }

        if (received != null) {
          if (expected == null || expected != received) {
            internalOnError(Status.UNAVAILABLE
                .withDescription("Protocol error: received response out of order. Expected: "
                    + expected + ", Received: " + received)
                .asRuntimeException());
            return;
          }
          expectedResponses.poll();
        } else if (response.hasRequestBody()) {
          if (expected == EventType.REQUEST_HEADERS) {
            internalOnError(Status.UNAVAILABLE
                .withDescription("Protocol error: received request_body before request_headers response.")
                .asRuntimeException());
            return;
          }
        } else if (response.hasResponseBody()) {
          if (expected == EventType.RESPONSE_HEADERS) {
            internalOnError(Status.UNAVAILABLE
                .withDescription("Protocol error: received response_body before headers response.")
                .asRuntimeException());
            return;
          }
        }

        if (response.getRequestDrain()) {
          extProcStreamState.set(ExtProcStreamState.DRAINING);
          activateCall();
          halfCloseExtProcStream();
        }

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
                response.getRequestHeaders().getResponse().getHeaderMutation(),
                mutationFilter,
                mutator);
          }
          activateCall();
        }
        else if (response.hasRequestBody()) {
          if (validateCompressionSupport(response.getRequestBody())) {
            handleRequestBodyResponse(response.getRequestBody());
          }
        }
        else if (response.hasResponseHeaders()) {
          if (response.getResponseHeaders().hasResponse()) {
            if (response.getResponseHeaders().getResponse().getStatus()
                == CommonResponse.ResponseStatus.CONTINUE_AND_REPLACE) {
              internalOnError(Status.UNAVAILABLE
                  .withDescription("CONTINUE_AND_REPLACE is not supported")
                  .asRuntimeException());
              return;
            }
            synchronized (streamLock) {
              applyHeaderMutations(
                  trailersOnly.get() ? savedTrailers : savedResponseHeaders,
                  response.getResponseHeaders().getResponse().getHeaderMutation(),
                  mutationFilter,
                  mutator);
            }
          }
          if (trailersOnly.get()) {
            proceedWithClose();
          } else {
            proceedWithSendHeaders();
          }
        }
        else if (response.hasResponseBody()) {
          System.out.println("JETS_LOG: handleExtProcResponse: took hasResponseBody branch");
          if (validateCompressionSupport(response.getResponseBody())) {
            handleResponseBodyResponse(response.getResponseBody());
          }
        }
        else if (response.hasResponseTrailers()) {
          if (response.getResponseTrailers().hasHeaderMutation()) {
            synchronized (streamLock) {
              applyHeaderMutations(
                  savedTrailers,
                  response.getResponseTrailers().getHeaderMutation(),
                  mutationFilter,
                  mutator);
            }
          }
          proceedWithClose();
        }

        checkEndOfStream();
      } catch (Throwable t) {
        internalOnError(t);
      }
    }

    void handleExtProcError(Throwable t) {
      if (markExtProcStreamFailed(extProcStreamState)) {
        synchronized (streamLock) {
          extProcClientCallRequestObserver = null;
        }
        if (config.getObservabilityMode()
            || (config.getFailureModeAllow() && !bodyMessageSentToExtProc.get())) {
          handleFailOpen();
        } else {
          proceedWithClose(
              Status.INTERNAL.withDescription("External processor stream failed")
                  .withCause(t),
              new Metadata());
        }
      }
    }

    void handleExtProcCompleted() {
      ExtProcStreamState state = extProcStreamState.get();
      if (state == ExtProcStreamState.DRAINING) {
        if (markExtProcStreamCompleted(extProcStreamState)) {
          handleFailOpen();
        }
      } else if (state == ExtProcStreamState.ACTIVE) {
        internalOnError(Status.UNAVAILABLE
            .withDescription("External processor stream completed without drain")
            .asRuntimeException());
      }
    }

    private void halfCloseExtProcStream() {
      synchronized (streamLock) {
        if (!isExtProcStreamCompleted() && extProcClientCallRequestObserver != null) {
          extProcClientCallRequestObserver.onCompleted();
        }
      }
    }

    private boolean isSidecarReady() {
      if (isExtProcStreamCompleted()) {
        return true;
      }
      if (isExtProcStreamDraining()) {
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
      if (isExtProcStreamCompleted()) {
        return super.isReady();
      }
      if (dataPlaneCallState.get() == DataPlaneCallState.IDLE && !config.getObservabilityMode()) {
        return false;
      }
      synchronized (streamLock) {
        boolean sidecarReady = isSidecarReady();
        if (config.getObservabilityMode()) {
          return super.isReady() && sidecarReady;
        }
        return upstreamToSidestreamWindow > 0 && sidecarReady
            && pendingResponseBodyMessages.isEmpty();
      }
    }

    @Override
    public void request(int numMessages) {
      if (passThroughMode.get() || isExtProcStreamCompleted()) {
        super.request(numMessages);
        return;
      }
      if (currentProcessingMode.getRequestBodyMode() != ProcessingMode.BodySendMode.GRPC) {
        synchronized (streamLock) {
          if (isSidecarReady()) {
            super.request(numMessages);
          } else {
            pendingRequests.addAndGet(numMessages);
          }
        }
        return;
      }
      if (config.getObservabilityMode()) {
        synchronized (streamLock) {
          if (isSidecarReady()) {
            super.request(numMessages);
          } else {
            pendingRequests.addAndGet(numMessages);
          }
        }
        return;
      }

      synchronized (streamLock) {
        pendingAppRequests.addAndGet(numMessages);
      }

      int satisfied = drainPendingMutatedRequestBodies();

      synchronized (streamLock) {
        int remaining = numMessages - satisfied;
        if (remaining > 0) {
          pendingTransportRequests.addAndGet(remaining);
        }
      }

      drainPendingRequests();
    }

    @Override
    public void sendHeaders(Metadata headers) {
      serverHeadersStartNanos = System.nanoTime();
      responseHeadersSent.set(true);
      boolean sendResponseHeaders =
          currentProcessingMode.getResponseHeaderMode() == ProcessingMode.HeaderSendMode.SEND
          || currentProcessingMode.getResponseHeaderMode()
              == ProcessingMode.HeaderSendMode.DEFAULT;

      synchronized (streamLock) {
        // NOTE: Even if sendResponseHeaders is false, we MUST obtain streamLock to call
        // proceedWithSendHeaders() safely, because an active control plane thread could
        // concurrently call super.sendMessage() or super.close() (e.g., due to a concurrent error).
        if (passThroughMode.get() || isExtProcStreamCompleted() || !sendResponseHeaders) {
          proceedWithSendHeaders(headers);
          return;
        }
        this.savedResponseHeaders = headers;
        if (isExtProcStreamDraining()) {
          return;
        }
      }

      sendToExtProc(ProcessingRequest.newBuilder()
          .setResponseHeaders(HttpHeaders.newBuilder()
              .setHeaders(toHeaderMap(headers, config.getForwardRulesConfig()))
              .build())
          .build());

      if (config.getObservabilityMode()) {
        synchronized (streamLock) {
          proceedWithSendHeaders();
        }
      }
    }

    void proceedWithSendHeaders() {
      synchronized (streamLock) {
        if (savedResponseHeaders != null) {
          proceedWithSendHeaders(savedResponseHeaders);
          savedResponseHeaders = null;
          InputStream msg;
          while ((msg = savedOutgoingMessagesAwaitingHeaderMutation.poll()) != null) {
            sendMessage(msg);
          }
          if (savedStatus != null) {
            if (!config.getObservabilityMode()
                && (!pendingResponseBodyMessages.isEmpty() || outstandingResponseBodyRequests > 0)) {
              pendingClose.set(true);
            } else {
              triggerCloseHandshake(savedTrailers);
            }
          }
        }
      }
    }

    private void proceedWithSendHeaders(Metadata headers) {
      if (serverHeadersStartNanos > 0) {
        long durationNanos = System.nanoTime() - serverHeadersStartNanos;
        recordDuration(serverHeadersDuration, durationNanos);
        serverHeadersStartNanos = 0;
      }
      super.sendHeaders(headers);
    }

    @Override
    public void sendMessage(InputStream message) {
      if (dataPlaneCallClosed.get()) {
        return;
      }

      if (passThroughMode.get()) {
        super.sendMessage(message);
        return;
      }

      try {
        ByteString bodyByteString = outboundStreamToByteString(message);
        ProcessingRequest requestToSend = null;
        boolean sendRawImmediately = false;

        synchronized (streamLock) {
          if (passThroughMode.get()) {
            sendRawImmediately = true;
          } else if (savedResponseHeaders != null) {
            savedOutgoingMessagesAwaitingHeaderMutation.add(new KnownLengthInputStream(bodyByteString));
          } else if (isExtProcStreamDraining() || isExtProcStreamCompleted()) {
            pendingDrainingOutgoingMessages.add(new KnownLengthInputStream(bodyByteString));
          } else if (currentProcessingMode.getResponseBodyMode() == ProcessingMode.BodySendMode.NONE) {
            sendRawImmediately = true;
          } else if (config.getObservabilityMode()) {
            sendRawImmediately = true;
            requestToSend = prepareResponseBodyRequest(bodyByteString);
          } else {
            // Flow control active
            if (upstreamToSidestreamWindow <= 0 || !pendingResponseBodyMessages.isEmpty()) {
              pendingResponseBodyMessages.add(bodyByteString);
            } else {
              upstreamToSidestreamWindow -= bodyByteString.size();
              requestToSend = prepareResponseBodyRequest(bodyByteString);
            }
          }
        }

        if (sendRawImmediately) {
          super.sendMessage(new KnownLengthInputStream(bodyByteString));
          if (requestToSend != null) {
            sendToExtProc(requestToSend);
          }
        } else if (requestToSend != null) {
          sendToExtProc(requestToSend);
        }
      } catch (IOException e) {
        proceedWithClose(
            Status.INTERNAL.withDescription("Failed to serialize response body").withCause(e),
            new Metadata());
      }
    }

    @Override
    public void close(Status status, Metadata trailers) {
      if (!closeCalled.compareAndSet(false, true)) {
        return;
      }
      serverTrailersStartNanos = System.nanoTime();

      if (isExtProcStreamFailed()
          && !config.getObservabilityMode()
          && (!config.getFailureModeAllow() || bodyMessageSentToExtProc.get())) {
        if (markDataPlaneCallClosed(dataPlaneCallState)) {
          proceedWithClose(
              Status.INTERNAL.withDescription("External processor stream failed")
                  .withCause(status.getCause()),
              new Metadata());
        }
        return;
      }

      synchronized (streamLock) {
        if (passThroughMode.get()) {
          if (markDataPlaneCallClosed(dataPlaneCallState)) {
            proceedWithClose(status, trailers);
          }
          closeExtProcStream();
          return;
        }

        this.savedStatus = status;
        this.savedTrailers = trailers;

        if (!config.getObservabilityMode()
            && (!pendingResponseBodyMessages.isEmpty() || outstandingResponseBodyRequests > 0)) {
          pendingClose.set(true);
          return;
        }

        if (isExtProcStreamCompleted()) {
          proceedWithClose();
          return;
        }

        if (savedResponseHeaders != null) {
          return;
        }
      }

      if (!responseHeadersSent.get()) {
        trailersOnly.set(true);
      }

      triggerCloseHandshake(trailers);

      if (config.getObservabilityMode()) {
        synchronized (streamLock) {
          proceedWithClose();
        }
        @SuppressWarnings("unused")
        ScheduledFuture<?> unused = scheduler.schedule(
            this::closeExtProcStream,
            config.getDeferredCloseTimeoutNanos(),
            TimeUnit.NANOSECONDS);
      }
    }

    void proceedWithClose() {
      synchronized (streamLock) {
        if (savedStatus != null
            && (isExtProcStreamCompleted() || config.getObservabilityMode())) {
          if (markDataPlaneCallClosed(dataPlaneCallState)) {
            proceedWithClose(savedStatus, savedTrailers);
          }
          savedStatus = null;
          savedTrailers = null;
        }
      }
    }

    void proceedWithClose(Status status, Metadata trailers) {
      if (dataPlaneCallClosed.compareAndSet(false, true)) {

        if (serverTrailersStartNanos > 0) {
          long durationNanos = System.nanoTime() - serverTrailersStartNanos;
          recordDuration(serverTrailersDuration, durationNanos);
          serverTrailersStartNanos = 0;
        }
        super.close(status, trailers);
      }
    }

    private void triggerCloseHandshake(Metadata trailers) {
      System.out.println("JETS_LOG: triggerCloseHandshake: trailersOnly=" + trailersOnly.get()
          + ", isExtProcStreamCompleted=" + isExtProcStreamCompleted()
          + ", terminationTriggered=" + terminationTriggered.get()
          + ", isRequestSideCompleted=" + isRequestSideCompleted());
      if (isExtProcStreamDraining()) {
        return;
      }
      if (isExtProcStreamCompleted() || !terminationTriggered.compareAndSet(false, true)) {
        return;
      }

      if (config.getObservabilityMode()) {
        boolean sendResponseHeaders =
            currentProcessingMode.getResponseHeaderMode() == ProcessingMode.HeaderSendMode.SEND
            || currentProcessingMode.getResponseHeaderMode()
                == ProcessingMode.HeaderSendMode.DEFAULT;
        boolean sendResponseTrailers =
            currentProcessingMode.getResponseTrailerMode() == ProcessingMode.HeaderSendMode.SEND;

        if (trailersOnly.get()) {
          if (sendResponseHeaders) {
            sendToExtProc(ProcessingRequest.newBuilder()
                .setResponseHeaders(HttpHeaders.newBuilder()
                    .setHeaders(toHeaderMap(trailers, config.getForwardRulesConfig()))
                    .setEndOfStream(true)
                    .build())
                .build());
          }
        } else if (sendResponseTrailers) {
          sendToExtProc(ProcessingRequest.newBuilder()
              .setResponseTrailers(HttpTrailers.newBuilder()
                  .setTrailers(toHeaderMap(trailers, config.getForwardRulesConfig()))
                  .build())
              .build());
        }
        proceedWithClose();
        closeExtProcStream();
        return;
      }

      boolean sendResponseHeaders =
          currentProcessingMode.getResponseHeaderMode() == ProcessingMode.HeaderSendMode.SEND
          || currentProcessingMode.getResponseHeaderMode()
              == ProcessingMode.HeaderSendMode.DEFAULT;


      boolean sendResponseTrailers =
          currentProcessingMode.getResponseTrailerMode() == ProcessingMode.HeaderSendMode.SEND;

      if (trailersOnly.get()) {
        if (sendResponseHeaders) {
          sendToExtProc(ProcessingRequest.newBuilder()
              .setResponseHeaders(HttpHeaders.newBuilder()
                  .setHeaders(toHeaderMap(trailers, config.getForwardRulesConfig()))
                  .setEndOfStream(true)
                  .build())
              .build());
        } else {
          proceedWithClose();
          if (!config.getObservabilityMode()) {
            closeExtProcStream();
          }
        }
      } else if (sendResponseTrailers) {
        isProcessingTrailers.set(true);
        sendToExtProc(ProcessingRequest.newBuilder()
            .setResponseTrailers(HttpTrailers.newBuilder()
                .setTrailers(toHeaderMap(trailers, config.getForwardRulesConfig()))
                .build())
            .build());
      } else {
        if (isRequestSideCompleted()) {
          unblockAfterStreamComplete();
          closeExtProcStream();
        }
      }
    }

    @GuardedBy("streamLock")
    private ProcessingRequest prepareResponseBodyRequest(ByteString body) {
      if (isExtProcStreamCompleted()
          || currentProcessingMode.getResponseBodyMode() != ProcessingMode.BodySendMode.GRPC) {
        return null;
      }

      HttpBody.Builder bodyBuilder = HttpBody.newBuilder()
          .setBody(body)
          .setEndOfStream(false);
      bodyMessageSentToExtProc.set(true);

      ProcessingRequest.Builder builder = ProcessingRequest.newBuilder()
          .setResponseBody(bodyBuilder.build());
      mergeAccumulatedWindowUpdates(builder);
      return builder.build();
    }

    void drainPendingResponseBodyMessages() {
      boolean triggerClose = false;
      while (true) {
        ProcessingRequest request = null;
        synchronized (streamLock) {
          if (upstreamToSidestreamWindow > 0 && !pendingResponseBodyMessages.isEmpty()) {
            ByteString body = pendingResponseBodyMessages.poll();
            upstreamToSidestreamWindow -= body.size();
            request = prepareResponseBodyRequest(body);
          }
          if (request == null) {
            if (pendingResponseBodyMessages.isEmpty() && pendingClose.get() && outstandingResponseBodyRequests == 0) {
              triggerClose = true;
              pendingClose.set(false);
            }
            break;
          }
        }
        if (request != null) {
          sendToExtProc(request);
        }
      }
      if (triggerClose) {
        synchronized (streamLock) {
          triggerCloseHandshake(savedTrailers);
        }
      }
    }

    private void handleRequestBodyResponse(BodyResponse bodyResponse) {
      if (bodyResponse.hasResponse() && bodyResponse.getResponse().hasBodyMutation()) {
        BodyMutation mutation = bodyResponse.getResponse().getBodyMutation();
        if (mutation.hasStreamedResponse()) {
          StreamedBodyResponse streamed = mutation.getStreamedResponse();
          final int bodySize = streamed.getBody().size();
          synchronized (streamLock) {
            sidestreamToUpstreamWindow -= bodySize;
          }
          deliverRequestBody(streamed);
          trySendAccumulatedWindowUpdates();
        }
      }
    }

    private void deliverRequestBody(StreamedBodyResponse streamed) {
      synchronized (streamLock) {
        pendingMutatedRequestBodies.add(streamed);
      }
      drainPendingMutatedRequestBodies();
    }

    int drainPendingMutatedRequestBodies() {
      List<StreamedBodyResponse> toDeliver = new ArrayList<>();
      synchronized (streamLock) {
        while (true) {
          if (pendingMutatedRequestBodies.isEmpty()) {
            break;
          }
          StreamedBodyResponse peeked = pendingMutatedRequestBodies.peek();
          if (peeked.getEndOfStreamWithoutMessage()) {
            toDeliver.add(pendingMutatedRequestBodies.poll());
          } else if (pendingAppRequests.get() > 0) {
            pendingAppRequests.decrementAndGet();
            toDeliver.add(pendingMutatedRequestBodies.poll());
          } else {
            break;
          }
        }
      }
      for (StreamedBodyResponse streamed : toDeliver) {
        final StreamedBodyResponse finalStreamed = streamed;
        final int bodySize = streamed.getBody().size();
        callContext.run(() -> {
          try {
            if (!finalStreamed.getEndOfStreamWithoutMessage()) {
              wrappedListener.onExternalBody(finalStreamed.getBody());
            }
            if (finalStreamed.getEndOfStream() || finalStreamed.getEndOfStreamWithoutMessage()) {
              wrappedListener.proceedWithHalfClose();
            }
          } finally {
            synchronized (streamLock) {
              accumulatedWindowUpdateSidestreamToUpstream += bodySize;
            }
            trySendAccumulatedWindowUpdates();
          }
        });
      }
      return toDeliver.size();
    }

    private void handleResponseBodyResponse(BodyResponse bodyResponse) {
      if (dataPlaneCallClosed.get()) {
        return;
      }
      if (bodyResponse.hasResponse() && bodyResponse.getResponse().hasBodyMutation()) {
        BodyMutation mutation = bodyResponse.getResponse().getBodyMutation();
        if (mutation.hasStreamedResponse()) {
          StreamedBodyResponse streamed = mutation.getStreamedResponse();
          ByteString body = streamed.getBody();
          final int bodySize = body.size();
          synchronized (streamLock) {
            sidestreamToDownstreamWindow -= bodySize;
          }
          deliverResponseBodyToClient(body);
          trySendAccumulatedWindowUpdates();
        }
      }

      synchronized (streamLock) {
        outstandingResponseBodyRequests--;
        System.out.println("JETS_LOG: handleResponseBodyResponse: pendingClose=" + pendingClose.get()
            + ", pendingMsgEmpty=" + pendingResponseBodyMessages.isEmpty()
            + ", outstanding=" + outstandingResponseBodyRequests);
        if (pendingClose.get() && pendingResponseBodyMessages.isEmpty() && outstandingResponseBodyRequests == 0) {
          System.out.println("JETS_LOG: handleResponseBodyResponse: triggering close");
          pendingClose.set(false);
          triggerCloseHandshake(savedTrailers);
        }
      }
    }

    private void deliverResponseBodyToClient(ByteString body) {
      boolean shouldSend = false;
      synchronized (streamLock) {
        System.out.println("JETS_LOG: deliverResponseBodyToClient: method=" + getMethodDescriptor().getFullMethodName()
            + ", type=" + getMethodDescriptor().getType()
            + ", super.isReady()=" + super.isReady()
            + ", pendingDownstreamBodyMessages.isEmpty()=" + pendingDownstreamBodyMessages.isEmpty());
        if (super.isReady() && pendingDownstreamBodyMessages.isEmpty()) {
          shouldSend = true;
        } else {
          System.out.println("JETS_LOG: deliverResponseBodyToClient: buffering message of size " + body.size());
          pendingDownstreamBodyMessages.add(body);
        }
      }
      if (shouldSend) {
        final int bodySize = body.size();
        super.sendMessage(new KnownLengthInputStream(body));
        synchronized (streamLock) {
          accumulatedWindowUpdateSidestreamToDownstream += bodySize;
        }
        trySendAccumulatedWindowUpdates();
      }
    }

    void drainPendingDownstreamBodyMessages() {
      while (true) {
        ByteString body = null;
        synchronized (streamLock) {
          if (super.isReady() && !pendingDownstreamBodyMessages.isEmpty()) {
            body = pendingDownstreamBodyMessages.poll();
          }
          if (body == null) {
            break;
          }
        }
        if (body != null) {
          final int bodySize = body.size();
          super.sendMessage(new KnownLengthInputStream(body));
          synchronized (streamLock) {
            accumulatedWindowUpdateSidestreamToDownstream += bodySize;
          }
          trySendAccumulatedWindowUpdates();
        }
      }
    }

    private void handleImmediateResponse(ImmediateResponse immediate)
        throws HeaderMutationDisallowedException {
      Status status = Status.fromCodeValue(immediate.getGrpcStatus().getStatus());
      if (!immediate.getDetails().isEmpty()) {
        status = status.withDescription(immediate.getDetails());
      }

      Metadata trailers = new Metadata();
      if (immediate.hasHeaders()) {
        applyHeaderMutations(trailers, immediate.getHeaders(), mutationFilter, mutator);
      }

      synchronized (streamLock) {
        savedStatus = status;
        savedTrailers = trailers;
      }

      if (isProcessingTrailers.get()) {
        unblockAfterStreamComplete();
      } else {
        proceedWithClose(status, trailers);
        unblockAfterStreamComplete();
      }
      closeExtProcStream();
    }

    private void drainPendingDrainingOutgoingMessages() {
      synchronized (streamLock) {
        InputStream msg;
        while ((msg = pendingDrainingOutgoingMessages.poll()) != null) {
          super.sendMessage(msg);
        }
        passThroughMode.set(true);
      }
    }

    private void drainRequestMessagesFailOpen() {
      List<StreamedBodyResponse> mutatedToDeliver = new ArrayList<>();
      synchronized (streamLock) {
        StreamedBodyResponse streamed;
        while ((streamed = pendingMutatedRequestBodies.poll()) != null) {
          mutatedToDeliver.add(streamed);
        }
      }
      for (StreamedBodyResponse streamed : mutatedToDeliver) {
        final StreamedBodyResponse finalStreamed = streamed;
        callContext.run(() -> {
          if (!finalStreamed.getEndOfStreamWithoutMessage()) {
            wrappedListener.onExternalBody(finalStreamed.getBody());
          }
          if (finalStreamed.getEndOfStream() || finalStreamed.getEndOfStreamWithoutMessage()) {
            wrappedListener.proceedWithHalfClose();
          }
        });
      }

      List<ByteString> rawToDeliver = new ArrayList<>();
      synchronized (streamLock) {
        ByteString body;
        while ((body = pendingRequestBodyMessages.poll()) != null) {
          rawToDeliver.add(body);
        }
      }
      for (ByteString body : rawToDeliver) {
        final ByteString finalBody = body;
        callContext.run(() -> wrappedListener.onExternalBody(finalBody));
      }

      wrappedListener.drainSavedMessages();
    }

    void drainResponseMessagesFailOpen() {
      boolean triggerClose = false;
      while (true) {
        Object msg = null;
        boolean isByteString = false;

        synchronized (streamLock) {
          if (super.isReady() && !pendingDownstreamBodyMessages.isEmpty()) {
            msg = pendingDownstreamBodyMessages.poll();
            isByteString = true;
          } else if (super.isReady() && pendingDownstreamBodyMessages.isEmpty()
              && !pendingResponseBodyMessages.isEmpty()) {
            msg = pendingResponseBodyMessages.poll();
            isByteString = true;
          } else if (super.isReady() && pendingDownstreamBodyMessages.isEmpty()
              && pendingResponseBodyMessages.isEmpty()
              && !savedOutgoingMessagesAwaitingHeaderMutation.isEmpty()) {
            msg = savedOutgoingMessagesAwaitingHeaderMutation.poll();
            isByteString = false;
          } else if (super.isReady() && pendingDownstreamBodyMessages.isEmpty()
              && pendingResponseBodyMessages.isEmpty()
              && savedOutgoingMessagesAwaitingHeaderMutation.isEmpty()
              && !pendingDrainingOutgoingMessages.isEmpty()) {
            msg = pendingDrainingOutgoingMessages.poll();
            isByteString = false;
          }

          if (msg == null) {
            if (pendingDownstreamBodyMessages.isEmpty()
                && pendingResponseBodyMessages.isEmpty()
                && savedOutgoingMessagesAwaitingHeaderMutation.isEmpty()
                && pendingDrainingOutgoingMessages.isEmpty()) {
              passThroughMode.set(true);
              if (pendingClose.get()) {
                triggerClose = true;
                pendingClose.set(false);
              }
            }
            break;
          }
        }

        if (msg != null) {
          if (isByteString) {
            super.sendMessage(new KnownLengthInputStream((ByteString) msg));
          } else {
            super.sendMessage((InputStream) msg);
          }
        }
      }
      if (triggerClose) {
        proceedWithClose();
      }
    }

    private void handleFailOpen() {
      activateCall();
      drainRequestMessagesFailOpen();
      proceedWithSendHeaders();
      drainResponseMessagesFailOpen();
      closeExtProcStream();
      wrappedListener.onReadyNotify();
    }

    /**
     * Evaluates whether the external processor stream can be safely closed and the
     * data plane call terminated.
     *
     * <p>This method acts as a cleanup checkpoint. It is invoked when request-side
     * processing completes (e.g., half-close) or when call termination is triggered.
     *
     * <p>The stream is only closed if:
     * <ul>
     *   <li>Call termination has been initiated ({@code terminationTriggered} is true).</li>
     *   <li>The request side of the call is fully completed ({@code isRequestSideCompleted}
      *       is true).</li>
     *   <li>There are no outstanding response-side messages (such as mutated response headers
     *       or trailers) expected from the external processor.</li>
     * </ul>
     *
     * <p>If all conditions are met, the data plane call is unblocked to allow the close status
     * and trailers to be propagated, and the external processor gRPC stream is terminated.
     */
    private void checkEndOfStream() {
      if (terminationTriggered.get() && isRequestSideCompleted()
          && !expectedResponses.contains(EventType.RESPONSE_HEADERS)
          && !expectedResponses.contains(EventType.RESPONSE_TRAILERS)) {
        unblockAfterStreamComplete();
        closeExtProcStream();
      }
    }

    private boolean isRequestSideCompleted() {
      return currentProcessingMode.getRequestBodyMode() != ProcessingMode.BodySendMode.GRPC
          || requestSideClosed.get();
    }

    void unblockAfterStreamComplete() {
      proceedWithSendHeaders();
      drainPendingDrainingOutgoingMessages();
      wrappedListener.drainSavedMessages();
      wrappedListener.onReadyNotify();
      proceedWithClose();
    }
  }

  static final class DataPlaneServerListener extends ServerCall.Listener<InputStream> {
    private final DataPlaneServerCall dataPlaneServerCall;
    final Queue<InputStream> savedMessages = new ConcurrentLinkedQueue<>();
    private volatile boolean halfCloseReceived;
    private volatile boolean halfCloseDeferred;
    private volatile ServerCall.Listener<InputStream> delegate;

    private DataPlaneServerListener(DataPlaneServerCall dataPlaneServerCall) {
      this.dataPlaneServerCall = dataPlaneServerCall;
    }

    void setDelegate(ServerCall.Listener<InputStream> delegate) {
      dataPlaneServerCall.triggerEvent(new SetDelegateEvent(delegate));
    }

    private void handleSetDelegate(ServerCall.Listener<InputStream> delegate) {
      this.delegate = delegate;
      dataPlaneServerCall.callContext.run(() -> {
        InputStream msg;
        while ((msg = savedMessages.poll()) != null) {
          delegate.onMessage(msg);
        }
        if (halfCloseReceived) {
          proceedWithHalfClose();
        }
      });
    }

    @Override
    public void onEvent(Object event) {
      System.out.println("JETS_LOG: onEvent: " + event);
      if (dataPlaneServerCall.dataPlaneCallClosed.get()) {
        return;
      }

      if (event instanceof ExtProcResponseEvent) {
        dataPlaneServerCall.handleExtProcResponse(((ExtProcResponseEvent) event).getResponse());
      } else if (event instanceof ExtProcErrorEvent) {
        dataPlaneServerCall.handleExtProcError(((ExtProcErrorEvent) event).getCause());
      } else if (event instanceof ExtProcCompletedEvent) {
        dataPlaneServerCall.handleExtProcCompleted();
      } else if (event instanceof ExtProcStreamReadyEvent) {
        dataPlaneServerCall.onExtProcStreamReady();
      } else if (event instanceof SetDelegateEvent) {
        handleSetDelegate(((SetDelegateEvent) event).getDelegate());
      }
    }

    void drainSavedMessages() {
      ServerCall.Listener<InputStream> del = delegate;
      if (del != null) {
        dataPlaneServerCall.callContext.run(() -> {
          InputStream msg;
          while ((msg = savedMessages.poll()) != null) {
            del.onMessage(msg);
          }
          if (halfCloseReceived) {
            proceedWithHalfClose();
          }
        });
      }
    }

    @Override
    public void onReady() {
      if (dataPlaneServerCall.passThroughMode.get()) {
        onReadyNotify();
        return;
      }
      if (dataPlaneServerCall.isExtProcStreamCompleted()) {
        dataPlaneServerCall.drainResponseMessagesFailOpen();
        return;
      }
      dataPlaneServerCall.drainPendingDownstreamBodyMessages();
      dataPlaneServerCall.drainPendingRequests();
      onReadyNotify();
    }

    void onReadyNotify() {
      ServerCall.Listener<InputStream> del = delegate;
      if (del != null && dataPlaneServerCall.isReady()) {
        dataPlaneServerCall.callContext.run(del::onReady);
      }
    }

    @Override
    public void onMessage(InputStream message) {
      if (dataPlaneServerCall.dataPlaneCallClosed.get()) {
        return;
      }
      if (dataPlaneServerCall.requestSideClosed.get()) {
        return;
      }
      ServerCall.Listener<InputStream> del = delegate;
      if (dataPlaneServerCall.passThroughMode.get() && del != null) {
        dataPlaneServerCall.callContext.run(() -> del.onMessage(message));
        return;
      }

      if (dataPlaneServerCall.isExtProcStreamCompleted()
          || dataPlaneServerCall.isExtProcStreamDraining()
          || dataPlaneServerCall.currentProcessingMode.getRequestBodyMode()
              != ProcessingMode.BodySendMode.GRPC
          || dataPlaneServerCall.config.getObservabilityMode()) {

        if (del == null || dataPlaneServerCall.isExtProcStreamDraining()) {
          try {
            ByteString copiedBytes = ByteString.readFrom(message);
            savedMessages.add(new KnownLengthInputStream(copiedBytes));
          } catch (IOException e) {
            dataPlaneServerCall.proceedWithClose(
                Status.INTERNAL.withDescription("Failed to buffer client request").withCause(e),
                new Metadata());
          }
        } else {
          dataPlaneServerCall.callContext.run(() -> del.onMessage(message));
        }
        return;
      }

      // Flow control active
      try {
        ByteString bodyByteString = ByteString.readFrom(message);
        synchronized (dataPlaneServerCall.streamLock) {
          // Re-check stream state under lock
          if (dataPlaneServerCall.isExtProcStreamCompleted()
              || dataPlaneServerCall.isExtProcStreamDraining()) {
            if (del == null || dataPlaneServerCall.isExtProcStreamDraining()) {
              savedMessages.add(new KnownLengthInputStream(bodyByteString));
            } else {
              dataPlaneServerCall.callContext.run(
                  () -> del.onMessage(new KnownLengthInputStream(bodyByteString)));
            }
            return;
          }

          if (dataPlaneServerCall.downstreamToSidestreamWindow <= 0
              || !dataPlaneServerCall.pendingRequestBodyMessages.isEmpty()) {
            dataPlaneServerCall.pendingRequestBodyMessages.add(bodyByteString);
          } else {
            sendRequestBodyToExtProc(bodyByteString);
          }
        }
        dataPlaneServerCall.drainPendingRequests();
      } catch (IOException e) {
        dataPlaneServerCall.proceedWithClose(
            Status.INTERNAL.withDescription("Failed to read client request").withCause(e),
            new Metadata());
      }
    }

    @Override
    public void onHalfClose() {
      System.out.println("JETS_LOG: onHalfClose");
      if (dataPlaneServerCall.dataPlaneCallClosed.get()) {
        return;
      }
      if (dataPlaneServerCall.requestSideClosed.get()) {
        return;
      }
      dataPlaneServerCall.clientHalfCloseStartNanos = System.nanoTime();
      halfCloseReceived = true;
      if (dataPlaneServerCall.isExtProcStreamDraining()) {
        return;
      }
      ServerCall.Listener<InputStream> del = delegate;
      if ((dataPlaneServerCall.passThroughMode.get()
          || dataPlaneServerCall.isExtProcStreamCompleted()) && del != null) {
        proceedWithHalfClose();
        return;
      }

      if (dataPlaneServerCall.dataPlaneCallState.get() == DataPlaneCallState.IDLE) {
        halfCloseDeferred = true;
        return;
      }

      if (dataPlaneServerCall.currentProcessingMode.getRequestBodyMode()
          == ProcessingMode.BodySendMode.NONE) {
        proceedWithHalfClose();
        return;
      }

      synchronized (dataPlaneServerCall.streamLock) {
        if (!dataPlaneServerCall.pendingRequestBodyMessages.isEmpty()) {
          halfCloseDeferred = true;
        } else {
          sendHalfCloseToExtProc();
        }
      }
      if (dataPlaneServerCall.config.getObservabilityMode()) {
        proceedWithHalfClose();
      }
    }

    void handleDeferredHalfClose() {
      if (dataPlaneServerCall.currentProcessingMode.getRequestBodyMode()
              == ProcessingMode.BodySendMode.NONE
          || dataPlaneServerCall.isExtProcStreamCompleted()) {
        proceedWithHalfClose();
      } else {
        synchronized (dataPlaneServerCall.streamLock) {
          sendHalfCloseToExtProc();
        }
      }
    }

    void proceedWithHalfClose() {
      System.out.println("JETS_LOG: proceedWithHalfClose");
      ServerCall.Listener<InputStream> del = delegate;
      if (del == null) {
        halfCloseReceived = true;
        return;
      }
      if (!dataPlaneServerCall.requestSideClosed.compareAndSet(false, true)) {
        return;
      }
      halfCloseReceived = true;
      if (dataPlaneServerCall.clientHalfCloseStartNanos > 0) {
        long durationNanos = System.nanoTime() - dataPlaneServerCall.clientHalfCloseStartNanos;
        dataPlaneServerCall.recordDuration(clientHalfCloseDuration, durationNanos);
        dataPlaneServerCall.clientHalfCloseStartNanos = 0;
      }
      dataPlaneServerCall.callContext.run(del::onHalfClose);
      dataPlaneServerCall.checkEndOfStream();
    }

    void onExternalBody(ByteString body) {
      ServerCall.Listener<InputStream> del = delegate;
      // In the future, if zero-copy reads are needed downstream, this can be optimized
      // by wrapping the ByteString in an InputStream that implements HasByteBuffer,
      // KnownLength, and Detachable.
      if (del != null) {
        dataPlaneServerCall.callContext.run(() -> del.onMessage(body.newInput()));
      } else {
        savedMessages.add(body.newInput());
      }
    }

    @GuardedBy("dataPlaneServerCall.streamLock")
    private void sendRequestBodyToExtProc(ByteString bodyByteString) {
      if (dataPlaneServerCall.isExtProcStreamCompleted()
          || dataPlaneServerCall.currentProcessingMode.getRequestBodyMode()
              != ProcessingMode.BodySendMode.GRPC) {
        return;
      }

      dataPlaneServerCall.downstreamToSidestreamWindow -= bodyByteString.size();
      dataPlaneServerCall.bodyMessageSentToExtProc.set(true);

      HttpBody.Builder bodyBuilder = HttpBody.newBuilder()
          .setBody(bodyByteString)
          .setEndOfStream(false);

      ProcessingRequest.Builder builder = ProcessingRequest.newBuilder()
          .setRequestBody(bodyBuilder.build());
      dataPlaneServerCall.mergeAccumulatedWindowUpdates(builder);
      dataPlaneServerCall.sendToExtProc(builder.build());
    }

    @GuardedBy("dataPlaneServerCall.streamLock")
    private void sendHalfCloseToExtProc() {
      if (dataPlaneServerCall.isExtProcStreamCompleted()
          || dataPlaneServerCall.currentProcessingMode.getRequestBodyMode()
              != ProcessingMode.BodySendMode.GRPC) {
        return;
      }

      HttpBody.Builder bodyBuilder = HttpBody.newBuilder()
          .setEndOfStreamWithoutMessage(true);

      ProcessingRequest.Builder builder = ProcessingRequest.newBuilder()
          .setRequestBody(bodyBuilder.build());
      dataPlaneServerCall.mergeAccumulatedWindowUpdates(builder);
      dataPlaneServerCall.sendToExtProc(builder.build());
    }

    void drainPendingRequestBodyMessages() {
      boolean triggerHalfClose = false;
      while (true) {
        ProcessingRequest request = null;
        synchronized (dataPlaneServerCall.streamLock) {
          if (dataPlaneServerCall.downstreamToSidestreamWindow > 0
              && !dataPlaneServerCall.pendingRequestBodyMessages.isEmpty()) {
            ByteString body = dataPlaneServerCall.pendingRequestBodyMessages.poll();
            dataPlaneServerCall.downstreamToSidestreamWindow -= body.size();
            dataPlaneServerCall.bodyMessageSentToExtProc.set(true);

            HttpBody.Builder bodyBuilder = HttpBody.newBuilder()
                .setBody(body)
                .setEndOfStream(false);
            ProcessingRequest.Builder builder = ProcessingRequest.newBuilder()
                .setRequestBody(bodyBuilder.build());
            dataPlaneServerCall.mergeAccumulatedWindowUpdates(builder);
            request = builder.build();
          }

          if (request == null) {
            if (dataPlaneServerCall.pendingRequestBodyMessages.isEmpty()
                && halfCloseDeferred) {
              triggerHalfClose = true;
              halfCloseDeferred = false;
            }
            break;
          }
        }

        if (request != null) {
          dataPlaneServerCall.sendToExtProc(request);
        }
      }

      if (triggerHalfClose) {
        synchronized (dataPlaneServerCall.streamLock) {
          sendHalfCloseToExtProc();
        }
      }
    }

    @Override
    public void onCancel() {
      dataPlaneServerCall.cancelExtProcStream(
          Status.CANCELLED.withDescription("Client cancelled RPC").asRuntimeException());
      ServerCall.Listener<InputStream> del = delegate;
      if (del != null) {
        dataPlaneServerCall.callContext.run(del::onCancel);
      }
    }

    @Override
    public void onComplete() {
      ServerCall.Listener<InputStream> del = delegate;
      if (del != null) {
        dataPlaneServerCall.callContext.run(del::onComplete);
      }
    }
  }

  static final class ExtProcResponseEvent {
    private final ProcessingResponse response;

    ExtProcResponseEvent(ProcessingResponse response) {
      this.response = response;
    }

    ProcessingResponse getResponse() {
      return response;
    }
  }

  static final class ExtProcErrorEvent {
    private final Throwable cause;

    ExtProcErrorEvent(Throwable cause) {
      this.cause = cause;
    }

    Throwable getCause() {
      return cause;
    }
  }

  static final class ExtProcCompletedEvent {}

  static final class ExtProcStreamReadyEvent {}

  static final class SetDelegateEvent {
    private final ServerCall.Listener<InputStream> delegate;

    SetDelegateEvent(ServerCall.Listener<InputStream> delegate) {
      this.delegate = delegate;
    }

    ServerCall.Listener<InputStream> getDelegate() {
      return delegate;
    }
  }
}
