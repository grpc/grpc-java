package io.grpc.xds;

import static com.google.common.base.Preconditions.checkNotNull;

import com.google.common.collect.ImmutableList;
import com.google.common.io.ByteStreams;
import com.google.protobuf.Any;
import com.google.protobuf.InvalidProtocolBufferException;
import com.google.protobuf.Message;
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
import io.grpc.ManagedChannel;
import io.grpc.Metadata;
import io.grpc.MethodDescriptor;
import io.grpc.Status;
import io.grpc.stub.ClientCallStreamObserver;
import io.grpc.stub.ClientResponseObserver;
import io.grpc.xds.internal.grpcservice.CachedChannelManager;
import io.grpc.xds.internal.grpcservice.GrpcServiceConfig;
import io.grpc.xds.internal.grpcservice.GrpcServiceConfigParser;
import io.grpc.xds.internal.grpcservice.GrpcServiceParseException;
import io.grpc.xds.internal.grpcservice.GrpcServiceXdsContextProvider;
import io.grpc.xds.internal.grpcservice.HeaderValue;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.Executor;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import javax.annotation.Nullable;

public class ExternalProcessorFilter implements Filter {
  static final String TYPE_URL = "type.googleapis.com/envoy.extensions.filters.http.ext_proc.v3.ExternalProcessor";

  final String filterInstanceName;

  public ExternalProcessorFilter(String name) {
    filterInstanceName = checkNotNull(name, "name");
  }

  static final class Provider implements Filter.Provider {
    private GrpcServiceXdsContextProvider grpcServiceXdsContextProvider;
    @Override
    public String[] typeUrls() {
      return new String[]{TYPE_URL};
    }

    @Override
    public boolean isClientFilter() {
      return true;
    }

    @Override
    public ExternalProcessorFilter newInstance(String name, GrpcServiceXdsContextProvider grpcServiceXdsContextProvider) {
      this.grpcServiceXdsContextProvider = grpcServiceXdsContextProvider;
      return new ExternalProcessorFilter(name);
    }

    @Override
    public ConfigOrError<ExternalProcessorFilterConfig> parseFilterConfig(Message rawProtoMessage) {
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
      if (mode.getRequestBodyMode() != ProcessingMode.BodySendMode.GRPC) {
        return ConfigOrError.fromError("Invalid request_body_mode: " + mode.getRequestBodyMode() + ". Only GRPC is supported.");
      }
      if (mode.getResponseBodyMode() != ProcessingMode.BodySendMode.GRPC) {
        return ConfigOrError.fromError("Invalid response_body_mode: " + mode.getResponseBodyMode() + ". Only GRPC is supported.");
      }

      try {
        GrpcServiceConfig grpcServiceConfig = GrpcServiceConfigParser.parse(externalProcessor.getGrpcService(), grpcServiceXdsContextProvider);
        return ConfigOrError.fromConfig(new ExternalProcessorFilterConfig(externalProcessor, grpcServiceConfig));
      } catch (GrpcServiceParseException e) {
        return ConfigOrError.fromError("Error parsing GrpcService config: " + e.getMessage());
      }
    }

    @Override
    public ConfigOrError<? extends FilterConfig> parseFilterConfigOverride(Message rawProtoMessage) {
      return parseFilterConfig(rawProtoMessage);
    }
  }

  @Nullable
  @Override
  public ClientInterceptor buildClientInterceptor(FilterConfig filterConfig,
                                                  @Nullable FilterConfig overrideConfig, java.util.concurrent.ScheduledExecutorService scheduler) {
    return new ExternalProcessorInterceptor((ExternalProcessorFilterConfig) filterConfig, scheduler);
  }

  static final class ExternalProcessorFilterConfig implements FilterConfig {

    private final ExternalProcessor externalProcessor;
    private final GrpcServiceConfig grpcServiceConfig;

    ExternalProcessorFilterConfig(ExternalProcessor externalProcessor, GrpcServiceConfig grpcServiceConfig) {
      this.externalProcessor = externalProcessor;
      this.grpcServiceConfig = grpcServiceConfig;
    }

    @Override
    public String typeUrl() {
      return "type.googleapis.com/envoy.extensions.filters.http.ext_proc.v3.ExternalProcessor";
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
                                 java.util.concurrent.ScheduledExecutorService scheduler) {
      this(filterConfig, new CachedChannelManager(), scheduler);
    }

    ExternalProcessorInterceptor(ExternalProcessorFilterConfig filterConfig,
                                 CachedChannelManager cachedChannelManager,
                                 java.util.concurrent.ScheduledExecutorService scheduler) {
      this.filterConfig = filterConfig;
      this.cachedChannelManager = checkNotNull(cachedChannelManager, "cachedChannelManager");
      this.scheduler = checkNotNull(scheduler, "scheduler");
    }

    @Override
    public <ReqT, RespT> ClientCall<ReqT, RespT> interceptCall(
        MethodDescriptor<ReqT, RespT> method,
        CallOptions callOptions,
        Channel next) {
      ExternalProcessorGrpc.ExternalProcessorStub stub = ExternalProcessorGrpc.newStub(
              cachedChannelManager.getChannel(filterConfig.grpcServiceConfig))
          .withExecutor(callOptions.getExecutor());

      if (filterConfig.grpcServiceConfig.timeout() != null && filterConfig.grpcServiceConfig.timeout().isPresent()) {
        long timeoutSeconds = filterConfig.grpcServiceConfig.timeout().get().getSeconds();
        int timeoutNanos = filterConfig.grpcServiceConfig.timeout().get().getNano();
        if (timeoutSeconds > 0 || timeoutNanos > 0) {
          stub = stub.withDeadlineAfter(timeoutSeconds * 1_000_000_000L + timeoutNanos, TimeUnit.NANOSECONDS);
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

      ExternalProcessor config = filterConfig.externalProcessor;

      MethodDescriptor<InputStream, InputStream> rawMethod = method.toBuilder(RAW_MARSHALLER, RAW_MARSHALLER).build();
      ClientCall<InputStream, InputStream> rawCall = next.newCall(rawMethod, callOptions);

      // Create a local subclass instance to buffer outbound actions
      ExtProcDelayedCall<InputStream, InputStream> delayedCall =
          new ExtProcDelayedCall<>(
              callOptions.getExecutor(), scheduler, callOptions.getDeadline());

      ExtProcClientCall extProcCall = new ExtProcClientCall(delayedCall, rawCall, stub, config);

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

    private static void applyHeaderMutations(Metadata metadata, io.envoyproxy.envoy.service.ext_proc.v3.HeaderMutation mutation) {
      for (io.envoyproxy.envoy.config.core.v3.HeaderValueOption setHeader : mutation.getSetHeadersList()) {
        String key = setHeader.getHeader().getKey();
        String value = setHeader.getHeader().getValue();
        try {
          Metadata.Key<String> metadataKey = Metadata.Key.of(key, Metadata.ASCII_STRING_MARSHALLER);
          if (setHeader.getAppendAction() == io.envoyproxy.envoy.config.core.v3.HeaderValueOption.HeaderAppendAction.APPEND_IF_EXISTS_OR_ADD
              || setHeader.getAppendAction() == io.envoyproxy.envoy.config.core.v3.HeaderValueOption.HeaderAppendAction.OVERWRITE_IF_EXISTS_OR_ADD) {
            metadata.removeAll(metadataKey);
          }
          metadata.put(metadataKey, value);
        } catch (IllegalArgumentException e) {
          // Skip
        }
      }
      for (String removeHeader : mutation.getRemoveHeadersList()) {
        try {
          Metadata.Key<String> metadataKey = Metadata.Key.of(removeHeader, Metadata.ASCII_STRING_MARSHALLER);
          metadata.removeAll(metadataKey);
        } catch (IllegalArgumentException e) {
          // Skip
        }
      }
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
      private final ExternalProcessor config;
      private final ClientCall<InputStream, InputStream> rawCall;
      private final ExtProcDelayedCall<InputStream, InputStream> delayedCall;
      private final Object streamLock = new Object();
      private io.grpc.stub.ClientCallStreamObserver<ProcessingRequest> extProcClientCallRequestObserver;
      private ExtProcListener wrappedListener;

      private Metadata requestHeaders;
      final AtomicBoolean extProcStreamFailed = new AtomicBoolean(false);
      final AtomicBoolean extProcStreamCompleted = new AtomicBoolean(false);
      final AtomicBoolean drainingExtProcStream = new AtomicBoolean(false);
      final AtomicBoolean halfClosed = new AtomicBoolean(false);

      protected ExtProcClientCall(
          ExtProcDelayedCall<InputStream, InputStream> delayedCall,
          ClientCall<InputStream, InputStream> rawCall,
          ExternalProcessorGrpc.ExternalProcessorStub stub,
          ExternalProcessor config) {
        super(delayedCall);
        this.delayedCall = delayedCall;
        this.rawCall = rawCall;
        this.stub = stub;
        this.config = config;
      }

      private void activateCall() {
        Runnable toRun = delayedCall.setCall(rawCall);
        if (toRun != null) {
          toRun.run();
        }
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
            extProcClientCallRequestObserver = requestStream;
          }

          @Override
          public void onNext(ProcessingResponse response) {
            try {
              if (response.hasImmediateResponse()) {
                handleImmediateResponse(response.getImmediateResponse(), responseListener);
                return;
              }

              if (config.getObservabilityMode()) {
                return;
              }

              if (response.getRequestDrain()) {
                drainingExtProcStream.set(true);
                synchronized (streamLock) {
                  extProcClientCallRequestObserver.onCompleted();
                }
                return;
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
                    synchronized (streamLock) {
                      extProcClientCallRequestObserver.onError(ex);
                    }
                    onError(ex);
                    return;
                  }
                }
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
                    synchronized (streamLock) {
                      extProcClientCallRequestObserver.onError(ex);
                    }
                    onError(ex);
                    return;
                  }
                }
                handleResponseBodyResponse(response.getResponseBody(), wrappedListener);
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
                synchronized (streamLock) {
                  extProcClientCallRequestObserver.onCompleted();
                }
              }
            }
            // For robustness. For any internal processing failure make sure the internal state
            // machine is notified and the dataplane call is properly cancelled (or failed-open if
            // configured)
            catch (Throwable t) {
              onError(t);
            }
          }

          @Override
          public void onError(Throwable t) {
            if (config.getFailureModeAllow()) {
              handleFailOpen(wrappedListener);
            } else {
              if (extProcStreamFailed.compareAndSet(false, true)) {
                rawCall.cancel("External processor stream failed", t);
              }
            }
          }

          @Override
          public void onCompleted() {
            drainingExtProcStream.set(false);
            handleFailOpen(wrappedListener);
          }
        });

        if (config.getObservabilityMode()) {
          extProcClientCallRequestObserver.setOnReadyHandler(this::onExtProcStreamReady);
        }

        // Send initial request headers. This is safe here because stub.process()
        // has started the call.
        synchronized (streamLock) {
          extProcClientCallRequestObserver.onNext(ProcessingRequest.newBuilder()
              .setRequestHeaders(io.envoyproxy.envoy.service.ext_proc.v3.HttpHeaders.newBuilder()
                  .setHeaders(toHeaderMap(headers))
                  .build())
              .build());
        }

        if (config.getObservabilityMode()) {
          activateCall();
        }
      }

      private void onExtProcStreamReady() {
        if (isReady()) {
          wrappedListener.onReady();
        }
      }

      @Override
      public boolean isReady() {
        if (extProcStreamCompleted.get()) {
          return super.isReady();
        }
        if (drainingExtProcStream.get()) {
          return false;
        }
        if (config.getObservabilityMode()) {
          synchronized (streamLock) {
            return super.isReady() && extProcClientCallRequestObserver != null
                && extProcClientCallRequestObserver.isReady();
          }
        }
        return super.isReady();
      }

      @Override
      public void request(int numMessages) {
        if (config.getObservabilityMode() && !isReady()) {
          return;
        }
        super.request(numMessages);
      }

      @Override
      public void sendMessage(InputStream message) {
        if (extProcStreamCompleted.get()) {
          super.sendMessage(message);
          return;
        }

        try {
          byte[] bodyBytes = ByteStreams.toByteArray(message);
          synchronized (streamLock) {
            if (!extProcStreamCompleted.get()) {
              extProcClientCallRequestObserver.onNext(ProcessingRequest.newBuilder()
                  .setRequestBody(io.envoyproxy.envoy.service.ext_proc.v3.HttpBody.newBuilder()
                      .setBody(com.google.protobuf.ByteString.copyFrom(bodyBytes))
                      .setEndOfStream(false)
                      .build())
                  .build());
            }
          }

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
          super.halfClose();
          return;
        }

        synchronized (streamLock) {
          if (!extProcStreamCompleted.get()) {
            extProcClientCallRequestObserver.onNext(ProcessingRequest.newBuilder()
                .setRequestBody(io.envoyproxy.envoy.service.ext_proc.v3.HttpBody.newBuilder()
                    .setEndOfStreamWithoutMessage(true)
                    .build())
                .build());
          }
        }

        super.halfClose();
      }

      @Override
      public void cancel(@Nullable String message, @Nullable Throwable cause) {
        synchronized (streamLock) {
          if (extProcClientCallRequestObserver != null) {
            extProcClientCallRequestObserver.onError(Status.CANCELLED.withDescription(message).withCause(cause).asRuntimeException());
          }
        }
        super.cancel(message, cause);
      }

      private void handleRequestBodyResponse(io.envoyproxy.envoy.service.ext_proc.v3.BodyResponse bodyResponse) {
        if (bodyResponse.hasResponse() && bodyResponse.getResponse().hasBodyMutation()) {
          io.envoyproxy.envoy.service.ext_proc.v3.BodyMutation mutation = bodyResponse.getResponse().getBodyMutation();
          if (mutation.hasBody() && !mutation.getBody().isEmpty()) {
            if (!halfClosed.get()) {
              byte[] mutatedBody = mutation.getBody().toByteArray();
              super.sendMessage(new ByteArrayInputStream(mutatedBody));
            }
          } else if (mutation.getClearBody()) {
            if (!halfClosed.get()) {
              super.sendMessage(new ByteArrayInputStream(new byte[0]));
            }
          }
        }
      }

      private void handleResponseBodyResponse(io.envoyproxy.envoy.service.ext_proc.v3.BodyResponse bodyResponse, ExtProcListener listener) {
        if (bodyResponse.hasResponse() && bodyResponse.getResponse().hasBodyMutation()) {
          io.envoyproxy.envoy.service.ext_proc.v3.BodyMutation mutation = bodyResponse.getResponse().getBodyMutation();
          if (mutation.hasBody() && !mutation.getBody().isEmpty()) {
            listener.onExternalBody(mutation.getBody());
          } else if (mutation.getClearBody()) {
            listener.onExternalBody(com.google.protobuf.ByteString.EMPTY);
          }
        }
      }

      private void handleImmediateResponse(io.envoyproxy.envoy.service.ext_proc.v3.ImmediateResponse immediate, Listener<InputStream> listener) {
        io.grpc.Status status = io.grpc.Status.fromCodeValue(immediate.getGrpcStatus().getStatus());
        rawCall.cancel("Rejected by ExtProc", null);
        listener.onClose(status, new Metadata());
        synchronized (streamLock) {
          extProcClientCallRequestObserver.onCompleted();
        }
      }

      private void handleFailOpen(ExtProcListener listener) {
        if (extProcStreamCompleted.compareAndSet(false, true)) {
          activateCall();
          listener.unblockAfterStreamComplete();
        }
      }
    }

    private static class ExtProcListener extends ForwardingClientCallListener.SimpleForwardingClientCallListener<InputStream> {
      private final ClientCall<?, ?> rawCall;
      private final ExtProcClientCall extProcClientCall;
      private Metadata savedHeaders;
      private Metadata savedTrailers;
      private io.grpc.Status savedStatus;

      protected ExtProcListener(ClientCall.Listener<InputStream> delegate, ClientCall<?, ?> rawCall,
                                ExtProcClientCall extProcClientCall) {
        super(delegate);
        this.rawCall = rawCall;
        this.extProcClientCall = extProcClientCall;
      }

      @Override
      public void onReady() {
        if (extProcClientCall.drainingExtProcStream.get()) {
          return;
        }
        if (extProcClientCall.isReady()) {
          super.onReady();
        }
      }

      @Override
      public void onHeaders(Metadata headers) {
        if (extProcClientCall.extProcStreamCompleted.get()) {
          super.onHeaders(headers);
          return;
        }
        this.savedHeaders = headers;
        synchronized (extProcClientCall.streamLock) {
          extProcClientCall.extProcClientCallRequestObserver.onNext(ProcessingRequest.newBuilder()
              .setResponseHeaders(io.envoyproxy.envoy.service.ext_proc.v3.HttpHeaders.newBuilder()
                  .setHeaders(toHeaderMap(headers))
                  .build())
              .build());
        }

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
        if (extProcClientCall.extProcStreamCompleted.get()) {
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
          super.onClose(Status.UNAVAILABLE.withDescription("External processor stream failed").withCause(status.getCause()), new Metadata());
          return;
        }
        if (extProcClientCall.extProcStreamCompleted.get()) {
          super.onClose(status, trailers);
          return;
        }

        this.savedStatus = status;
        this.savedTrailers = trailers;

        sendResponseBodyToExtProc(null, true);

        synchronized (extProcClientCall.streamLock) {
          extProcClientCall.extProcClientCallRequestObserver.onNext(ProcessingRequest.newBuilder()
              .setResponseTrailers(io.envoyproxy.envoy.service.ext_proc.v3.HttpTrailers.newBuilder()
                  .setTrailers(toHeaderMap(savedTrailers))
                  .build())
              .build());
        }

        if (extProcClientCall.config.getObservabilityMode()) {
          super.onClose(status, trailers);
          synchronized (extProcClientCall.streamLock) {
            extProcClientCall.extProcClientCallRequestObserver.onCompleted();
          }
        }
      }

      private void sendResponseBodyToExtProc(@Nullable byte[] bodyBytes, boolean endOfStream) {
        if (extProcClientCall.extProcStreamCompleted.get()) {
          return;
        }

        io.envoyproxy.envoy.service.ext_proc.v3.HttpBody.Builder bodyBuilder =
            io.envoyproxy.envoy.service.ext_proc.v3.HttpBody.newBuilder();
        if (bodyBytes != null) {
          bodyBuilder.setBody(com.google.protobuf.ByteString.copyFrom(bodyBytes));
        }
        bodyBuilder.setEndOfStream(endOfStream);

        synchronized (extProcClientCall.streamLock) {
          extProcClientCall.extProcClientCallRequestObserver.onNext(ProcessingRequest.newBuilder()
              .setResponseBody(bodyBuilder.build())
              .build());
        }
      }

      void proceedWithClose() {
        if (savedStatus != null) {
          super.onClose(savedStatus, savedTrailers);
          savedStatus = null;
          savedTrailers = null;
        }
      }

      void onExternalBody(com.google.protobuf.ByteString body) {
        super.onMessage(body.newInput());
      }

      void unblockAfterStreamComplete() {
        proceedWithHeaders();
        proceedWithClose();
      }
    }
  }
}
