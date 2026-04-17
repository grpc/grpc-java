package io.grpc.xds;

import static com.google.common.base.Preconditions.checkNotNull;

import com.google.common.annotations.VisibleForTesting;
import com.google.common.collect.ImmutableList;
import com.google.common.io.ByteStreams;
import com.google.protobuf.Any;
import com.google.protobuf.InvalidProtocolBufferException;
import com.google.protobuf.Message;
import com.google.protobuf.util.Durations;
import io.envoyproxy.envoy.config.core.v3.GrpcService;
import io.envoyproxy.envoy.extensions.filters.http.ext_proc.v3.ExternalProcessor;
import io.envoyproxy.envoy.extensions.filters.http.ext_proc.v3.ProcessingMode;
import io.envoyproxy.envoy.service.ext_proc.v3.ExternalProcessorGrpc;
import io.envoyproxy.envoy.service.ext_proc.v3.ProcessingRequest;
import io.envoyproxy.envoy.service.ext_proc.v3.ProcessingResponse;
import io.grpc.Attributes;
import io.grpc.CallOptions;
import io.grpc.Channel;
import io.grpc.ClientCall;
import io.grpc.ClientInterceptor;
import io.grpc.ForwardingClientCall.SimpleForwardingClientCall;
import io.grpc.ForwardingClientCallListener;
import io.grpc.Metadata;
import io.grpc.MethodDescriptor;
import io.grpc.Status;
import io.grpc.internal.SerializingExecutor;
import io.grpc.stub.ClientCallStreamObserver;
import io.grpc.stub.ClientResponseObserver;
import io.grpc.xds.internal.grpcservice.CachedChannelManager;
import io.grpc.xds.internal.grpcservice.GrpcServiceConfig;
import io.grpc.xds.internal.grpcservice.GrpcServiceParseException;
import io.grpc.xds.internal.grpcservice.HeaderValue;
import io.grpc.xds.internal.headermutations.HeaderMutationDisallowedException;
import io.grpc.xds.internal.headermutations.HeaderMutationFilter;
import io.grpc.xds.internal.headermutations.HeaderMutationRulesConfig;
import io.grpc.xds.internal.headermutations.HeaderMutationRulesParseException;
import io.grpc.xds.internal.headermutations.HeaderMutationRulesParser;
import io.grpc.xds.internal.headermutations.HeaderMutations;
import io.grpc.xds.internal.headermutations.HeaderMutator;
import io.grpc.xds.internal.headermutations.HeaderValueOption;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.Executor;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import javax.annotation.Nullable;

public class ExternalProcessorFilter implements Filter {
  static final String TYPE_URL = "type.googleapis.com/envoy.extensions.filters.http.ext_proc.v3.ExternalProcessor";

  final String filterInstanceName;
  private final CachedChannelManager cachedChannelManager;

  public ExternalProcessorFilter(String name) {
    this(name, new CachedChannelManager());
  }

  ExternalProcessorFilter(String name, CachedChannelManager cachedChannelManager) {
    this.filterInstanceName = checkNotNull(name, "name");
    this.cachedChannelManager = checkNotNull(cachedChannelManager, "cachedChannelManager");
  }

  @Override
  public void close() {
    cachedChannelManager.close();
  }

  static final class Provider implements Filter.Provider {
    @Override
    public String[] typeUrls() {
      return new String[]{TYPE_URL};
    }

    @Override
    public boolean isClientFilter() {
      return true;
    }

    @Override
    public ExternalProcessorFilter newInstance(String name) {
      return new ExternalProcessorFilter(name);
    }

    @Override
    public ConfigOrError<ExternalProcessorFilterConfig> parseFilterConfig(
        Message rawProtoMessage, FilterContext context) {
      if (!(rawProtoMessage instanceof Any)) {
        return ConfigOrError.fromError("Invalid config type: " + rawProtoMessage.getClass());
      }
      ExternalProcessor externalProcessor;
      try {
        externalProcessor = ((Any) rawProtoMessage).unpack(ExternalProcessor.class);
      } catch (InvalidProtocolBufferException e) {
        return ConfigOrError.fromError("Invalid proto: " + e);
      }

      ProcessingMode mode = externalProcessor.getProcessingMode();
      if (mode.getRequestBodyMode() != ProcessingMode.BodySendMode.GRPC
          && mode.getRequestBodyMode() != ProcessingMode.BodySendMode.NONE) {
        return ConfigOrError.fromError("Invalid request_body_mode: " + mode.getRequestBodyMode()
            + ". Only GRPC and NONE are supported.");
      }
      if (mode.getResponseBodyMode() != ProcessingMode.BodySendMode.GRPC
          && mode.getResponseBodyMode() != ProcessingMode.BodySendMode.NONE) {
        return ConfigOrError.fromError("Invalid response_body_mode: " + mode.getResponseBodyMode()
            + ". Only GRPC and NONE are supported.");
      }

      HeaderMutationRulesConfig mutationRulesConfig = null;
      if (externalProcessor.hasMutationRules()) {
        try {
          mutationRulesConfig = HeaderMutationRulesParser.parse(externalProcessor.getMutationRules());
        } catch (HeaderMutationRulesParseException e) {
          return ConfigOrError.fromError("Error parsing HeaderMutationRules: " + e.getMessage());
        }
      }

      try {
        GrpcServiceConfig grpcServiceConfig = GrpcServiceConfigParser.parse(
            externalProcessor.getGrpcService(), context.bootstrapInfo(), context.serverInfo());
        
        long deferredCloseTimeoutNanos = TimeUnit.SECONDS.toNanos(5);
        if (externalProcessor.hasDeferredCloseTimeout()) {
          com.google.protobuf.Duration deferredCloseTimeout = externalProcessor.getDeferredCloseTimeout();
          try {
            Durations.checkValid(deferredCloseTimeout);
          } catch (IllegalArgumentException e) {
            return ConfigOrError.fromError("Invalid deferred_close_timeout: " + e.getMessage());
          }
          deferredCloseTimeoutNanos = Durations.toNanos(deferredCloseTimeout);
          if (deferredCloseTimeoutNanos <= 0) {
            return ConfigOrError.fromError("deferred_close_timeout must be positive");
          }
        }

        return ConfigOrError.fromConfig(new ExternalProcessorFilterConfig(
            externalProcessor, grpcServiceConfig, Optional.ofNullable(mutationRulesConfig), deferredCloseTimeoutNanos));
      } catch (GrpcServiceParseException e) {
        return ConfigOrError.fromError("Error parsing GrpcService config: " + e.getMessage());
      }
    }

    @Override
    public ConfigOrError<? extends FilterConfig> parseFilterConfigOverride(
        Message rawProtoMessage, FilterContext context) {
      return parseFilterConfig(rawProtoMessage, context);
    }
  }

  @Nullable
  @Override
  public ClientInterceptor buildClientInterceptor(FilterConfig filterConfig,
      @Nullable FilterConfig overrideConfig, java.util.concurrent.ScheduledExecutorService scheduler) {
    ExternalProcessorFilterConfig config = (ExternalProcessorFilterConfig) filterConfig;
    if (overrideConfig != null) {
      config = mergeConfigs(config, (ExternalProcessorFilterConfig) overrideConfig);
    }
    checkNotNull(config, "config");
    return new ExternalProcessorInterceptor(config, cachedChannelManager, scheduler);
  }

  private static ExternalProcessorFilterConfig mergeConfigs(
      ExternalProcessorFilterConfig parent, ExternalProcessorFilterConfig override) {
    ExternalProcessor parentProto = parent.getExternalProcessor();
    ExternalProcessor overrideProto = override.getExternalProcessor();
    ExternalProcessor.Builder mergedProtoBuilder = parentProto.toBuilder();

    GrpcServiceConfig mergedGrpcServiceConfig = parent.getGrpcServiceConfig();
    Optional<HeaderMutationRulesConfig> mergedMutationRulesConfig = parent.getMutationRulesConfig();
    long mergedDeferredCloseTimeoutNanos = parent.getDeferredCloseTimeoutNanos();

    for (Map.Entry<com.google.protobuf.Descriptors.FieldDescriptor, Object> entry
        : overrideProto.getAllFields().entrySet()) {
      mergedProtoBuilder.setField(entry.getKey(), entry.getValue());
      String fieldName = entry.getKey().getName();
      if (fieldName.equals("grpc_service")) {
        mergedGrpcServiceConfig = override.getGrpcServiceConfig();
      } else if (fieldName.equals("mutation_rules")) {
        mergedMutationRulesConfig = override.getMutationRulesConfig();
      } else if (fieldName.equals("deferred_close_timeout")) {
        mergedDeferredCloseTimeoutNanos = override.getDeferredCloseTimeoutNanos();
      }
    }

    ExternalProcessor mergedProto = mergedProtoBuilder.build();
    checkNotNull(mergedProto, "mergedProto");
    return new ExternalProcessorFilterConfig(
        mergedProto, mergedGrpcServiceConfig, mergedMutationRulesConfig, mergedDeferredCloseTimeoutNanos);
  }

  static final class ExternalProcessorFilterConfig implements FilterConfig {

    private final ExternalProcessor externalProcessor;
    private final GrpcServiceConfig grpcServiceConfig;
    private final Optional<HeaderMutationRulesConfig> mutationRulesConfig;
    private final boolean allowModeOverride;
    private final boolean disableImmediateResponse;
    private final ImmutableList<ProcessingMode> allowedOverrideModes;
    private final long deferredCloseTimeoutNanos;

    ExternalProcessorFilterConfig(ExternalProcessor externalProcessor,
        GrpcServiceConfig grpcServiceConfig, Optional<HeaderMutationRulesConfig> mutationRulesConfig,
        long deferredCloseTimeoutNanos) {
      this.externalProcessor = externalProcessor;
      this.grpcServiceConfig = grpcServiceConfig;
      this.mutationRulesConfig = mutationRulesConfig;
      this.allowModeOverride = externalProcessor.getAllowModeOverride();
      this.disableImmediateResponse = externalProcessor.getDisableImmediateResponse();
      this.allowedOverrideModes = ImmutableList.copyOf(externalProcessor.getAllowedOverrideModesList());
      this.deferredCloseTimeoutNanos = deferredCloseTimeoutNanos;
    }

    @Override
    public String typeUrl() {
      return TYPE_URL;
    }

    ExternalProcessor getExternalProcessor() {
      return externalProcessor;
    }

    GrpcServiceConfig getGrpcServiceConfig() {
      return grpcServiceConfig;
    }

    Optional<HeaderMutationRulesConfig> getMutationRulesConfig() {
      return mutationRulesConfig;
    }

    boolean getAllowModeOverride() {
      return allowModeOverride;
    }

    boolean getDisableImmediateResponse() {
      return disableImmediateResponse;
    }

    ImmutableList<ProcessingMode> getAllowedOverrideModes() {
      return allowedOverrideModes;
    }

    long getDeferredCloseTimeoutNanos() {
      return deferredCloseTimeoutNanos;
    }

    boolean getObservabilityMode() {
      return externalProcessor.getObservabilityMode();
    }

    boolean getFailureModeAllow() {
      return externalProcessor.getFailureModeAllow();
    }
  }

  static final class ExternalProcessorInterceptor implements ClientInterceptor {
    private final CachedChannelManager cachedChannelManager;
    private final ExternalProcessorFilterConfig filterConfig;
    private final java.util.concurrent.ScheduledExecutorService scheduler;

    private static final MethodDescriptor.Marshaller<InputStream> RAW_MARSHALLER =
        new MethodDescriptor.Marshaller<InputStream>() {
          @Override
          public InputStream stream(InputStream value) { return value; }
          @Override
          public InputStream parse(InputStream stream) { return stream; }
        };

    ExternalProcessorInterceptor(ExternalProcessorFilterConfig filterConfig,
        CachedChannelManager cachedChannelManager,
        java.util.concurrent.ScheduledExecutorService scheduler) {
      this.filterConfig = filterConfig;
      this.cachedChannelManager = checkNotNull(cachedChannelManager, "cachedChannelManager");
      this.scheduler = checkNotNull(scheduler, "scheduler");
    }

    @VisibleForTesting
    ExternalProcessorFilterConfig getFilterConfig() {
      return filterConfig;
    }

    @Override
    public <ReqT, RespT> ClientCall<ReqT, RespT> interceptCall(
        MethodDescriptor<ReqT, RespT> method,
        CallOptions callOptions,
        Channel next) {
      SerializingExecutor serializingExecutor = new SerializingExecutor(callOptions.getExecutor());
      callOptions = callOptions.withExecutor(serializingExecutor);
      ExternalProcessorGrpc.ExternalProcessorStub stub = ExternalProcessorGrpc.newStub(
          cachedChannelManager.getChannel(filterConfig.grpcServiceConfig))
          .withExecutor(serializingExecutor);
      
      if (filterConfig.grpcServiceConfig.timeout() != null && filterConfig.grpcServiceConfig.timeout().isPresent()) {
        long timeoutNanos = filterConfig.grpcServiceConfig.timeout().get().toNanos();
        if (timeoutNanos > 0) {
          stub = stub.withDeadlineAfter(timeoutNanos, TimeUnit.NANOSECONDS);
        }
      }

      ImmutableList<HeaderValue> initialMetadata = filterConfig.grpcServiceConfig.initialMetadata();
      if (initialMetadata != null && !initialMetadata.isEmpty()) {
        stub = stub.withInterceptors(new ClientInterceptor() {
          @Override
          public <ExtReqT, ExtRespT> ClientCall<ExtReqT, ExtRespT> interceptCall(
              MethodDescriptor<ExtReqT, ExtRespT> extMethod, CallOptions extCallOptions, Channel extNext) {
            return new SimpleForwardingClientCall<ExtReqT, ExtRespT>(extNext.newCall(extMethod, extCallOptions)) {
              @Override
              public void start(Listener<ExtRespT> responseListener, Metadata headers) {
                for (HeaderValue headerValue : initialMetadata) {
                  String key = headerValue.key();
                  if (key.endsWith(Metadata.BINARY_HEADER_SUFFIX)) {
                    if (headerValue.rawValue().isPresent()) {
                      Metadata.Key<byte[]> metadataKey = Metadata.Key.of(key, Metadata.BINARY_BYTE_MARSHALLER);
                      headers.put(metadataKey, headerValue.rawValue().get().toByteArray());
                    }
                  } else {
                    if (headerValue.value().isPresent()) {
                      Metadata.Key<String> metadataKey = Metadata.Key.of(key, Metadata.ASCII_STRING_MARSHALLER);
                      headers.put(metadataKey, headerValue.value().get());
                    }
                  }
                }
                super.start(responseListener, headers);
              }
            };
          }
        });
      }

      MethodDescriptor<InputStream, InputStream> rawMethod = method.toBuilder(RAW_MARSHALLER, RAW_MARSHALLER).build();
      ClientCall<InputStream, InputStream> rawCall = next.newCall(rawMethod, callOptions);

      // Create a local subclass instance to buffer outbound actions
      ExtProcDelayedCall<InputStream, InputStream> delayedCall =
          new ExtProcDelayedCall<>(
              serializingExecutor, scheduler, callOptions.getDeadline());

      ExtProcClientCall extProcCall = new ExtProcClientCall(
          delayedCall, rawCall, stub, filterConfig, filterConfig.getMutationRulesConfig(), scheduler);

      return new ClientCall<ReqT, RespT>() {
        @Override
        public void start(Listener<RespT> responseListener, Metadata headers) {
          extProcCall.start(new Listener<InputStream>() {
            @Override
            public void onHeaders(Metadata headers) {
              responseListener.onHeaders(headers);
            }

            @Override
            public void onMessage(InputStream message) {
              responseListener.onMessage(method.getResponseMarshaller().parse(message));
            }

            @Override
            public void onClose(Status status, Metadata trailers) {
              responseListener.onClose(status, trailers);
            }

            @Override
            public void onReady() {
              responseListener.onReady();
            }
          }, headers);
        }

        @Override
        public void request(int numMessages) {
          extProcCall.request(numMessages);
        }

        @Override
        public void cancel(@Nullable String message, @Nullable Throwable cause) {
          extProcCall.cancel(message, cause);
        }

        @Override
        public void halfClose() {
          extProcCall.halfClose();
        }

        @Override
        public void sendMessage(ReqT message) {
          extProcCall.sendMessage(method.getRequestMarshaller().stream(message));
        }

        @Override
        public boolean isReady() {
          return extProcCall.isReady();
        }

        @Override
        public void setMessageCompression(boolean enabled) {
          extProcCall.setMessageCompression(enabled);
        }

        @Override
        public Attributes getAttributes() {
          return extProcCall.getAttributes();
        }
      };
    }

    // --- SHARED UTILITY METHODS ---
    private static io.envoyproxy.envoy.config.core.v3.HeaderMap toHeaderMap(Metadata metadata) {
      io.envoyproxy.envoy.config.core.v3.HeaderMap.Builder builder =
          io.envoyproxy.envoy.config.core.v3.HeaderMap.newBuilder();

      for (String key : metadata.keys()) {
        // Skip binary headers for this basic mapping
        if (key.endsWith(Metadata.BINARY_HEADER_SUFFIX)) {
          Metadata.Key<byte[]> binKey = Metadata.Key.of(key, Metadata.BINARY_BYTE_MARSHALLER);
          Iterable<byte[]> values = metadata.getAll(binKey);
          if (values != null) {
            for (byte[] binValue : values) {
              String encoded = com.google.common.io.BaseEncoding.base64().encode(binValue);
              io.envoyproxy.envoy.config.core.v3.HeaderValue headerValue =
                  io.envoyproxy.envoy.config.core.v3.HeaderValue.newBuilder()
                      .setKey(key.toLowerCase(Locale.ROOT))
                      .setValue(encoded)
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
                      .setValue(value)
                      .build();
              builder.addHeaders(headerValue);
            }
          }
        }
      }
      return builder.build();
    }

    /**
     * A local subclass to expose the protected constructor of DelayedClientCall.
     */
    private static class ExtProcDelayedCall<ReqT, RespT> extends io.grpc.internal.DelayedClientCall<ReqT, RespT> {
      ExtProcDelayedCall(java.util.concurrent.Executor executor, java.util.concurrent.ScheduledExecutorService scheduler, @Nullable io.grpc.Deadline deadline) {
        super(executor, scheduler, deadline);
      }
    }

    /**
     * Handles the bidirectional stream with the External Processor.
     * Buffers the actual RPC start until the Ext Proc header response is received.
     */
    private static class ExtProcClientCall extends SimpleForwardingClientCall<InputStream, InputStream> {
      private final ExternalProcessorGrpc.ExternalProcessorStub stub;
      private final ExternalProcessorFilterConfig config;
      private final ClientCall<InputStream, InputStream> rawCall;
      private final ExtProcDelayedCall<InputStream, InputStream> delayedCall;
      private final java.util.concurrent.ScheduledExecutorService scheduler;
      private final Object streamLock = new Object();
      private volatile io.grpc.stub.ClientCallStreamObserver<ProcessingRequest> extProcClientCallRequestObserver;
      private final java.util.Queue<ProcessingRequest> pendingProcessingRequests = new java.util.concurrent.ConcurrentLinkedQueue<>();
      private volatile ExtProcListener wrappedListener;
      private final HeaderMutationFilter mutationFilter;
      private final HeaderMutator mutator = HeaderMutator.create();
      private final java.util.concurrent.atomic.AtomicInteger pendingRequests = new java.util.concurrent.atomic.AtomicInteger(0);
      private volatile ProcessingMode currentProcessingMode;

      private volatile Metadata requestHeaders;
      final AtomicBoolean extProcStreamFailed = new AtomicBoolean(false);
      final AtomicBoolean extProcStreamCompleted = new AtomicBoolean(false);
      final AtomicBoolean notifiedApp = new AtomicBoolean(false);
      final AtomicBoolean drainingExtProcStream = new AtomicBoolean(false);
      final AtomicBoolean halfClosed = new AtomicBoolean(false);
      final AtomicBoolean requestSideClosed = new AtomicBoolean(false);
      final AtomicBoolean isProcessingTrailers = new AtomicBoolean(false);

      protected ExtProcClientCall(
          ExtProcDelayedCall<InputStream, InputStream> delayedCall,
          ClientCall<InputStream, InputStream> rawCall,
          ExternalProcessorGrpc.ExternalProcessorStub stub,
          ExternalProcessorFilterConfig config,
          Optional<HeaderMutationRulesConfig> mutationRulesConfig,
          java.util.concurrent.ScheduledExecutorService scheduler) {
        super(delayedCall);
        this.delayedCall = delayedCall;
        this.rawCall = rawCall;
        this.stub = stub;
        this.config = config;
        this.currentProcessingMode = config.getExternalProcessor().getProcessingMode();
        this.mutationFilter = new HeaderMutationFilter(mutationRulesConfig);
        this.scheduler = scheduler;
      }

      private void activateCall() {
        if (extProcStreamFailed.get()) {
          return;
        }
        Runnable toRun = delayedCall.setCall(rawCall);
        if (toRun != null) {
          toRun.run();
        }
        drainPendingRequests();
        onReadyNotify();
      }

      private void applyHeaderMutations(Metadata metadata,
          io.envoyproxy.envoy.service.ext_proc.v3.HeaderMutation mutation)
          throws HeaderMutationDisallowedException {
        ImmutableList.Builder<HeaderValueOption> headersToModify = ImmutableList.builder();
        for (io.envoyproxy.envoy.config.core.v3.HeaderValueOption protoOption : mutation.getSetHeadersList()) {
          io.envoyproxy.envoy.config.core.v3.HeaderValue protoHeader = protoOption.getHeader();
          HeaderValue headerValue;
          if (protoHeader.getKey().endsWith(Metadata.BINARY_HEADER_SUFFIX)) {
            headerValue = HeaderValue.create(protoHeader.getKey(),
                com.google.protobuf.ByteString.copyFrom(
                    com.google.common.io.BaseEncoding.base64().decode(protoHeader.getValue())));
          } else {
            headerValue = HeaderValue.create(protoHeader.getKey(), protoHeader.getValue());
          }
          headersToModify.add(HeaderValueOption.create(
              headerValue,
              HeaderValueOption.HeaderAppendAction.valueOf(protoOption.getAppendAction().name()),
              protoOption.getKeepEmptyValue()));
        }

        HeaderMutations mutations = HeaderMutations.create(
            headersToModify.build(),
            ImmutableList.copyOf(mutation.getRemoveHeadersList()));

        HeaderMutations filteredMutations = mutationFilter.filter(mutations);
        mutator.applyMutations(filteredMutations, metadata);
      }

      @Override
      public void start(Listener<InputStream> responseListener, Metadata headers) {
        this.requestHeaders = headers;
        this.wrappedListener = new ExtProcListener(responseListener, rawCall, this);

        // DelayedClientCall.start will buffer the listener and headers until setCall is called.
        super.start(wrappedListener, headers);

        stub.process(new ClientResponseObserver<ProcessingRequest, ProcessingResponse>() {
          @Override
          public void beforeStart(ClientCallStreamObserver<ProcessingRequest> requestStream) {
            synchronized (streamLock) {
              extProcClientCallRequestObserver = requestStream;
              while (!pendingProcessingRequests.isEmpty()) {
                requestStream.onNext(pendingProcessingRequests.poll());
              }
            }
            requestStream.setOnReadyHandler(ExtProcClientCall.this::onExtProcStreamReady);
          }

          @Override
          public void onNext(ProcessingResponse response) {
            try {
              if (response.hasImmediateResponse()) {
                if (config.getDisableImmediateResponse()) {
                  onError(Status.INTERNAL
                      .withDescription("Immediate response is disabled but received from external processor")
                      .asRuntimeException());
                  return;
                }
                handleImmediateResponse(response.getImmediateResponse(), responseListener);
                return;
              }

              if (response.hasModeOverride()) {
                handleModeOverride(response.getModeOverride());
              }

              if (config.getObservabilityMode()) {
                return;
              }

              if (response.getRequestDrain()) {
                drainingExtProcStream.set(true);
                halfCloseExtProcStream();
                activateCall();
              }

              // 1. Client Headers
              if (response.hasRequestHeaders()) {
                if (response.getRequestHeaders().hasResponse()) {
                  applyHeaderMutations(requestHeaders, response.getRequestHeaders().getResponse().getHeaderMutation());
                }
                activateCall();
              }
              // 2. Client Message (Request Body)
              else if (response.hasRequestBody()) {
                if (response.getRequestBody().hasResponse()
                    && response.getRequestBody().getResponse().hasBodyMutation()) {
                  io.envoyproxy.envoy.service.ext_proc.v3.BodyMutation mutation = 
                      response.getRequestBody().getResponse().getBodyMutation();
                  if (mutation.hasStreamedResponse()
                      && mutation.getStreamedResponse().getGrpcMessageCompressed()) {
                    io.grpc.StatusRuntimeException ex = io.grpc.Status.INTERNAL
                        .withDescription("gRPC message compression not supported in ext_proc")
                        .asRuntimeException();
                    if (!extProcStreamCompleted.get() && extProcClientCallRequestObserver != null) {
                      extProcClientCallRequestObserver.onError(ex);
                    }
                    onError(ex);
                    return;
                  }                }
                handleRequestBodyResponse(response.getRequestBody());
              }
              // 4. Server Headers
              else if (response.hasResponseHeaders()) {
                if (response.getResponseHeaders().hasResponse()) {
                  applyHeaderMutations(wrappedListener.savedHeaders, response.getResponseHeaders().getResponse().getHeaderMutation());
                }
                wrappedListener.proceedWithHeaders();
              }
              // 5. Server Message (Response Body)
              else if (response.hasResponseBody()) {
                if (response.getResponseBody().hasResponse()
                    && response.getResponseBody().getResponse().hasBodyMutation()) {
                  io.envoyproxy.envoy.service.ext_proc.v3.BodyMutation mutation = 
                      response.getResponseBody().getResponse().getBodyMutation();
                  if (mutation.hasStreamedResponse()
                      && mutation.getStreamedResponse().getGrpcMessageCompressed()) {
                    io.grpc.StatusRuntimeException ex = io.grpc.Status.INTERNAL
                        .withDescription("gRPC message compression not supported in ext_proc")
                        .asRuntimeException();
                    if (!extProcStreamCompleted.get() && extProcClientCallRequestObserver != null) {
                      extProcClientCallRequestObserver.onError(ex);
                    }
                    onError(ex);
                    return;
                  }                }
                handleResponseBodyResponse(response.getResponseBody(), wrappedListener);
                if (response.getResponseBody().hasResponse() && response.getResponseBody().getResponse().hasBodyMutation()) {
                  io.envoyproxy.envoy.service.ext_proc.v3.BodyMutation mutation = response.getResponseBody().getResponse().getBodyMutation();
                  if (mutation.hasStreamedResponse() && (mutation.getStreamedResponse().getEndOfStream() || mutation.getStreamedResponse().getEndOfStreamWithoutMessage())) {
                     closeExtProcStream();
                  }
                }
              }
              // 6. Response Trailers
              if (response.hasResponseTrailers()) {
                if (response.getResponseTrailers().hasHeaderMutation()) {
                  applyHeaderMutations(
                      wrappedListener.savedTrailers,
                      response.getResponseTrailers().getHeaderMutation()
                  );
                }
                wrappedListener.proceedWithClose();
                closeExtProcStream();
              }
            } catch (Throwable t) {
              onError(t);
            }
          }

          @Override
          public void onError(Throwable t) {
            if (extProcStreamCompleted.compareAndSet(false, true)) {
              if (config.getFailureModeAllow()) {
                handleFailOpen(wrappedListener);
              } else {
                extProcStreamFailed.set(true);
                String message = "External processor stream failed";
                delayedCall.cancel(message, t);
              }
            }
          }

          @Override
          public void onCompleted() {
            if (extProcStreamCompleted.compareAndSet(false, true)) {
              drainingExtProcStream.set(false);
              handleFailOpen(wrappedListener);
            }
          }
        });

        boolean sendRequestHeaders = currentProcessingMode.getRequestHeaderMode()
            != ProcessingMode.HeaderSendMode.SKIP;

        if (sendRequestHeaders) {
          sendToExtProc(ProcessingRequest.newBuilder()
              .setRequestHeaders(io.envoyproxy.envoy.service.ext_proc.v3.HttpHeaders.newBuilder()
                  .setHeaders(toHeaderMap(headers))
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
          if (extProcStreamCompleted.get()) {
            return;
          }
          if (extProcClientCallRequestObserver != null) {
            extProcClientCallRequestObserver.onNext(request);
          } else {
            pendingProcessingRequests.add(request);
          }
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
          if (extProcStreamCompleted.compareAndSet(false, true)) {
            if (extProcClientCallRequestObserver != null) {
              extProcClientCallRequestObserver.onCompleted();
            }
          }
        }
      }

      private void halfCloseExtProcStream() {
        synchronized (streamLock) {
          if (!extProcStreamCompleted.get() && extProcClientCallRequestObserver != null) {
            extProcClientCallRequestObserver.onCompleted();
          }
        }
      }

      private void onReadyNotify() {
        wrappedListener.onReadyNotify();
      }

      private boolean isSidecarReady() {
        if (extProcStreamCompleted.get()) {
          return true;
        }
        if (drainingExtProcStream.get()) {
          return false;
        }
        synchronized (streamLock) {
          io.grpc.stub.ClientCallStreamObserver<ProcessingRequest> observer = extProcClientCallRequestObserver;
          return observer != null && observer.isReady();
        }
      }

      @Override
      public boolean isReady() {
        if (extProcStreamCompleted.get()) {
          return super.isReady();
        }
        boolean sidecarReady = isSidecarReady();
        if (config.getObservabilityMode()) {
          return super.isReady() && sidecarReady;
        }
        return sidecarReady;
      }

      @Override
      public void request(int numMessages) {
        if (extProcStreamCompleted.get()) {
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

        if (extProcStreamCompleted.get()) {
          super.sendMessage(message);
          return;
        }

        if (currentProcessingMode.getRequestBodyMode() == ProcessingMode.BodySendMode.NONE) {
          super.sendMessage(message);
          return;
        }

        // Mode is GRPC
        try {
          byte[] bodyBytes = ByteStreams.toByteArray(message);
          sendToExtProc(ProcessingRequest.newBuilder()
              .setRequestBody(io.envoyproxy.envoy.service.ext_proc.v3.HttpBody.newBuilder()
                  .setBody(com.google.protobuf.ByteString.copyFrom(bodyBytes))
                  .setEndOfStream(false)
                  .build())
              .build());

          if (config.getObservabilityMode()) {
            super.sendMessage(new ByteArrayInputStream(bodyBytes));
          }
        } catch (IOException e) {
          rawCall.cancel("Failed to serialize message for External Processor", e);
        }
      }

      @Override
      public void halfClose() {
        halfClosed.set(true);
        if (extProcStreamCompleted.get()) {
          if (requestSideClosed.compareAndSet(false, true)) {
            super.halfClose();
          }
          return;
        }

        if (currentProcessingMode.getRequestBodyMode() == ProcessingMode.BodySendMode.NONE) {
          if (requestSideClosed.compareAndSet(false, true)) {
            super.halfClose();
          }
          return;
        }

        // Mode is GRPC
        sendToExtProc(ProcessingRequest.newBuilder()
            .setRequestBody(io.envoyproxy.envoy.service.ext_proc.v3.HttpBody.newBuilder()
                .setEndOfStreamWithoutMessage(true)
                .build())
            .build());
        
        // Defer super.halfClose() until ext-proc response signals end_of_stream.
      }

      @Override
      public void cancel(@Nullable String message, @Nullable Throwable cause) {
        synchronized (streamLock) {
          if (!extProcStreamCompleted.get() && extProcClientCallRequestObserver != null) {
            extProcClientCallRequestObserver.onError(Status.CANCELLED.withDescription(message).withCause(cause).asRuntimeException());
          }
        }
        super.cancel(message, cause);
      }

      private void handleModeOverride(ProcessingMode modeOverride) {
        if (!config.getAllowModeOverride()) {
          return;
        }

        if (!config.getAllowedOverrideModes().isEmpty()) {
          boolean matched = false;
          for (ProcessingMode allowedMode : config.getAllowedOverrideModes()) {
            if (isModeMatch(allowedMode, modeOverride)) {
              matched = true;
              break;
            }
          }
          if (!matched) {
            return;
          }
        }
        ProcessingMode oldMode = currentProcessingMode;
        // The override is valid. Specification says request_header_mode cannot be overridden.
        currentProcessingMode = modeOverride.toBuilder()
            .setRequestHeaderMode(oldMode.getRequestHeaderMode())
            .build();

        // Special handling for enabling/disabling body modes
        if (oldMode.getResponseBodyMode() == ProcessingMode.BodySendMode.GRPC
            && currentProcessingMode.getResponseBodyMode() == ProcessingMode.BodySendMode.NONE) {
          wrappedListener.proceedWithHeaders();
          wrappedListener.proceedWithClose();
        }
      }

      private boolean isModeMatch(ProcessingMode allowedMode, ProcessingMode override) {
        // Specification says: matching will ignore the value of the request_header_mode field, 
        // since that mode cannot be overridden.
        return allowedMode.getRequestBodyMode() == override.getRequestBodyMode()
            && allowedMode.getResponseHeaderMode() == override.getResponseHeaderMode()
            && allowedMode.getResponseBodyMode() == override.getResponseBodyMode()
            && allowedMode.getRequestTrailerMode() == override.getRequestTrailerMode()
            && allowedMode.getResponseTrailerMode() == override.getResponseTrailerMode();
      }

      private void handleRequestBodyResponse(io.envoyproxy.envoy.service.ext_proc.v3.BodyResponse bodyResponse) {
        if (bodyResponse.hasResponse() && bodyResponse.getResponse().hasBodyMutation()) {
          io.envoyproxy.envoy.service.ext_proc.v3.BodyMutation mutation = bodyResponse.getResponse().getBodyMutation();
          if (mutation.hasStreamedResponse()) {
            io.envoyproxy.envoy.service.ext_proc.v3.StreamedBodyResponse streamed = mutation.getStreamedResponse();
            if (!streamed.getBody().isEmpty()) {
              super.sendMessage(streamed.getBody().newInput());
            }
            if (streamed.getEndOfStream() || streamed.getEndOfStreamWithoutMessage()) {
              if (requestSideClosed.compareAndSet(false, true)) {
                super.halfClose();
              }
            }
          }
        }
      }

      private void handleResponseBodyResponse(io.envoyproxy.envoy.service.ext_proc.v3.BodyResponse bodyResponse, ExtProcListener listener) {
        if (bodyResponse.hasResponse() && bodyResponse.getResponse().hasBodyMutation()) {
          io.envoyproxy.envoy.service.ext_proc.v3.BodyMutation mutation = bodyResponse.getResponse().getBodyMutation();
          if (mutation.hasStreamedResponse()) {
            io.envoyproxy.envoy.service.ext_proc.v3.StreamedBodyResponse streamed = mutation.getStreamedResponse();
            if (!streamed.getBody().isEmpty()) {
              listener.onExternalBody(streamed.getBody());
            }
            if (streamed.getEndOfStream() || streamed.getEndOfStreamWithoutMessage()) {
              if (requestSideClosed.compareAndSet(false, true)) {
                super.halfClose();
              }
              listener.proceedWithClose();
            }
          }
        }
      }

      private void handleImmediateResponse(io.envoyproxy.envoy.service.ext_proc.v3.ImmediateResponse immediate, Listener<InputStream> listener)
          throws HeaderMutationDisallowedException {
        Status status = Status.fromCodeValue(immediate.getGrpcStatus().getStatus());
        if (!immediate.getDetails().isEmpty()) {
          status = status.withDescription(immediate.getDetails());
        }

        Metadata trailers = new Metadata();
        if (immediate.hasHeaders()) {
          applyHeaderMutations(trailers, immediate.getHeaders());
        }

        if (isProcessingTrailers.get()) {
          // If sent in response to a server trailers event, sets the status and optionally headers to be included in the trailers.
          // Note: savedStatus is NOT null if isProcessingTrailers is true.
          if (extProcStreamCompleted.compareAndSet(false, true)) {
            wrappedListener.savedStatus = status;
            wrappedListener.savedTrailers = trailers;
            wrappedListener.proceedWithClose();
          }
        } else {
          // If sent in response to any other event, it will cause the data plane RPC to immediately fail 
          // with the specified status as if it were an out-of-band cancellation.
          if (extProcStreamCompleted.compareAndSet(false, true)) {
            if (notifiedApp.compareAndSet(false, true)) {
              rawCall.cancel(status.getDescription(), null);
              listener.onClose(status, trailers);
            }
          }
        }
        closeExtProcStream();
      }

      private void handleFailOpen(ExtProcListener listener) {
        activateCall();
        listener.unblockAfterStreamComplete();
      }
    }

    private static class ExtProcListener extends ForwardingClientCallListener.SimpleForwardingClientCallListener<InputStream> {
      private final ClientCall<?, ?> rawCall;
      private final ExtProcClientCall extProcClientCall;
      private volatile Metadata savedHeaders;
      private volatile Metadata savedTrailers;
      private volatile io.grpc.Status savedStatus;

      protected ExtProcListener(ClientCall.Listener<InputStream> delegate, ClientCall<?, ?> rawCall,
                                ExtProcClientCall extProcClientCall) {
        super(delegate);
        this.rawCall = rawCall;
        this.extProcClientCall = extProcClientCall;
      }

      @Override
      public void onReady() {
        extProcClientCall.drainPendingRequests();
        onReadyNotify();
      }

      void onReadyNotify() {
        super.onReady();
      }

      @Override
      public void onHeaders(Metadata headers) {
        if (extProcClientCall.extProcStreamCompleted.get()
            || extProcClientCall.currentProcessingMode.getResponseHeaderMode() != ProcessingMode.HeaderSendMode.SEND) {
          super.onHeaders(headers);
          return;
        }
        this.savedHeaders = headers;
        extProcClientCall.sendToExtProc(ProcessingRequest.newBuilder()
            .setResponseHeaders(io.envoyproxy.envoy.service.ext_proc.v3.HttpHeaders.newBuilder()
                .setHeaders(toHeaderMap(headers))
                .build())
            .build());

        if (extProcClientCall.config.getObservabilityMode()) {
          super.onHeaders(headers);
        }
      }

      void proceedWithHeaders() {
        if (savedHeaders != null) {
          super.onHeaders(savedHeaders);
          savedHeaders = null;
        }
      }

      @Override
      public void onMessage(InputStream message) {
        if (extProcClientCall.extProcStreamCompleted.get()
            || extProcClientCall.currentProcessingMode.getResponseBodyMode() != ProcessingMode.BodySendMode.GRPC) {
          super.onMessage(message);
          return;
        }

        try {
          byte[] bodyBytes = ByteStreams.toByteArray(message);
          sendResponseBodyToExtProc(bodyBytes, false);

          if (extProcClientCall.config.getObservabilityMode()) {
            super.onMessage(new ByteArrayInputStream(bodyBytes));
          }
        } catch (IOException e) {
           rawCall.cancel("Failed to read server response", e);
        }
      }

      @Override
      public void onClose(io.grpc.Status status, Metadata trailers) {
        if (extProcClientCall.extProcStreamFailed.get()) {
          if (extProcClientCall.notifiedApp.compareAndSet(false, true)) {
            super.onClose(Status.UNAVAILABLE.withDescription("External processor stream failed").withCause(status.getCause()), new Metadata());
          }
          return;
        }
        if (extProcClientCall.extProcStreamCompleted.get()) {
          if (extProcClientCall.notifiedApp.compareAndSet(false, true)) {
            super.onClose(status, trailers);
          }
          return;
        }

        this.savedStatus = status;
        this.savedTrailers = trailers;

        if (extProcClientCall.currentProcessingMode.getResponseTrailerMode() == ProcessingMode.HeaderSendMode.SEND) {
          extProcClientCall.isProcessingTrailers.set(true);
        }

        if (extProcClientCall.currentProcessingMode.getResponseBodyMode() == ProcessingMode.BodySendMode.GRPC) {
          sendResponseBodyToExtProc(null, true);
        }

        if (extProcClientCall.currentProcessingMode.getResponseTrailerMode() == ProcessingMode.HeaderSendMode.SEND) {
          extProcClientCall.sendToExtProc(ProcessingRequest.newBuilder()
              .setResponseTrailers(io.envoyproxy.envoy.service.ext_proc.v3.HttpTrailers.newBuilder()
                  .setTrailers(toHeaderMap(savedTrailers))
                  .build())
              .build());
        } else {
          // If we are not sending trailers, and not waiting for body EOS, proceed with close.
          if (extProcClientCall.currentProcessingMode.getResponseBodyMode() != ProcessingMode.BodySendMode.GRPC) {
            proceedWithClose();
            if (!extProcClientCall.config.getObservabilityMode()) {
              extProcClientCall.closeExtProcStream();
            }
          }
        }

        if (extProcClientCall.config.getObservabilityMode()) {
          super.onClose(status, trailers);
          @SuppressWarnings("unused")
          java.util.concurrent.ScheduledFuture<?> unused = extProcClientCall.scheduler.schedule(
              extProcClientCall::closeExtProcStream,
              extProcClientCall.config.getDeferredCloseTimeoutNanos(),
              TimeUnit.NANOSECONDS);
        }
      }

      private void sendResponseBodyToExtProc(@Nullable byte[] bodyBytes, boolean endOfStream) {
        if (extProcClientCall.extProcStreamCompleted.get()
            || extProcClientCall.currentProcessingMode.getResponseBodyMode() != ProcessingMode.BodySendMode.GRPC) {
          return;
        }

        io.envoyproxy.envoy.service.ext_proc.v3.HttpBody.Builder bodyBuilder =
            io.envoyproxy.envoy.service.ext_proc.v3.HttpBody.newBuilder();
        if (bodyBytes != null) {
          bodyBuilder.setBody(com.google.protobuf.ByteString.copyFrom(bodyBytes));
        }
        bodyBuilder.setEndOfStream(endOfStream);

        extProcClientCall.sendToExtProc(ProcessingRequest.newBuilder()
            .setResponseBody(bodyBuilder.build())
            .build());
      }

      void proceedWithClose() {
        if (savedStatus != null) {
          if (extProcClientCall.notifiedApp.compareAndSet(false, true)) {
            super.onClose(savedStatus, savedTrailers);
          }
          savedStatus = null;
          savedTrailers = null;
        }
      }

      void onExternalBody(com.google.protobuf.ByteString body) {
        super.onMessage(body.newInput());
      }

      void unblockAfterStreamComplete() {
        proceedWithHeaders();
        onReadyNotify();
        proceedWithClose();
      }
    }
  }
}
