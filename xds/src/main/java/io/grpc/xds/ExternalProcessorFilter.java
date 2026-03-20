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
      @Nullable FilterConfig overrideConfig, ScheduledExecutorService scheduler) {
    return new ExternalProcessorInterceptor((ExternalProcessorFilterConfig) filterConfig);
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

    private static final MethodDescriptor.Marshaller<InputStream> RAW_MARSHALLER =
        new MethodDescriptor.Marshaller<InputStream>() {
          @Override
          public InputStream stream(InputStream value) { return value; }
          @Override
          public InputStream parse(InputStream stream) { return stream; }
        };

    ExternalProcessorInterceptor(ExternalProcessorFilterConfig filterConfig) {
      this(filterConfig, new CachedChannelManager());
    }

    ExternalProcessorInterceptor(ExternalProcessorFilterConfig filterConfig,
        CachedChannelManager cachedChannelManager) {
      this.filterConfig = filterConfig;
      this.cachedChannelManager = checkNotNull(cachedChannelManager, "cachedChannelManager");
    }

    @Override
    public <ReqT, RespT> ClientCall<ReqT, RespT> interceptCall(
        MethodDescriptor<ReqT, RespT> method,
        CallOptions callOptions,
        Channel next) {
      ExternalProcessorGrpc.ExternalProcessorStub stub = ExternalProcessorGrpc.newStub(
          cachedChannelManager.getChannel(filterConfig.grpcServiceConfig));
      
      if (filterConfig.grpcServiceConfig.timeout() != null && filterConfig.grpcServiceConfig.timeout().isPresent()) {
        long timeoutNanos = filterConfig.grpcServiceConfig.timeout().get().getSeconds() * 1_000_000_000L 
                            + filterConfig.grpcServiceConfig.timeout().get().getNano();
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

      ExternalProcessor config = filterConfig.externalProcessor;

      MethodDescriptor<InputStream, InputStream> rawMethod = method.toBuilder(RAW_MARSHALLER, RAW_MARSHALLER).build();
      ClientCall<InputStream, InputStream> rawCall = next.newCall(rawMethod, callOptions);

      ExtProcClientCall extProcCall = new ExtProcClientCall(rawCall, stub, config);

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
          for (byte[] binValue : metadata.getAll(binKey)) {
            String encoded = com.google.common.io.BaseEncoding.base64().encode(binValue);
            io.envoyproxy.envoy.config.core.v3.HeaderValue headerValue =
                io.envoyproxy.envoy.config.core.v3.HeaderValue.newBuilder()
                    .setKey(key.toLowerCase()) // Envoy expects lowercase keys, following the same convention here
                    .setValue(encoded)
                    .build();
            builder.addHeaders(headerValue);
          }
        } else {
          Metadata.Key<String> asciiKey = Metadata.Key.of(key, Metadata.ASCII_STRING_MARSHALLER);
          Iterable<String> values = metadata.getAll(asciiKey);

          if (values != null) {
            for (String value : values) {
              io.envoyproxy.envoy.config.core.v3.HeaderValue headerValue =
                  io.envoyproxy.envoy.config.core.v3.HeaderValue.newBuilder()
                      .setKey(key.toLowerCase()) // Envoy expects lowercase keys, following the same convention here
                      .setValue(value)
                      .build();
              builder.addHeaders(headerValue);
            }
          }
        }
      }
      return builder.build();
    }

    private static void applyHeaderMutations(Metadata headers, io.envoyproxy.envoy.service.ext_proc.v3.HeaderMutation mutation) {
      // 1. Process Set/Add/Append operations
      for (io.envoyproxy.envoy.config.core.v3.HeaderValueOption opt : mutation.getSetHeadersList()) {
        String keyStr = opt.getHeader().getKey().toLowerCase();
        String valueStr = opt.getHeader().getValue();
        boolean isBinary = keyStr.endsWith(Metadata.BINARY_HEADER_SUFFIX);

        if (isBinary) {
          Metadata.Key<byte[]> key = Metadata.Key.of(keyStr, Metadata.BINARY_BYTE_MARSHALLER);
          if (!opt.getAppend().getValue()) {
            headers.discardAll(key);
          }
          // Decode Base64 string from ExtProc back to raw bytes for gRPC
          byte[] decodedValue = com.google.common.io.BaseEncoding.base64().decode(valueStr);
          headers.put(key, decodedValue);
        } else {
          Metadata.Key<String> key = Metadata.Key.of(keyStr, Metadata.ASCII_STRING_MARSHALLER);
          if (!opt.getAppend().getValue()) {
            headers.discardAll(key);
          }
          headers.put(key, valueStr);
        }
      }

      // 2. Process Remove operations
      for (String keyToRemove : mutation.getRemoveHeadersList()) {
        String lowKey = keyToRemove.toLowerCase();
        if (lowKey.endsWith(Metadata.BINARY_HEADER_SUFFIX)) {
          headers.discardAll(Metadata.Key.of(lowKey, Metadata.BINARY_BYTE_MARSHALLER));
        } else {
          headers.discardAll(Metadata.Key.of(lowKey, Metadata.ASCII_STRING_MARSHALLER));
        }
      }
    }

    /**
     * Handles the bidirectional stream with the External Processor.
     * Buffers the actual RPC start until the Ext Proc header response is received.
     */
    private static class ExtProcClientCall extends SimpleForwardingClientCall<InputStream, InputStream> {
      private final ExternalProcessorGrpc.ExternalProcessorStub stub;
      private final ExternalProcessor config;
      private final Object lock = new Object();
      private ClientCallStreamObserver<ProcessingRequest> extProcClientCallRequestObserver;
      private ExtProcListener wrappedListener;

      private volatile boolean headersSent = false;
      private Metadata requestHeaders;
      private final java.util.Queue<Runnable> pendingActions = new java.util.concurrent.ConcurrentLinkedQueue<>();
      final AtomicBoolean extProcStreamFailed = new AtomicBoolean(false);
      final AtomicBoolean extProcStreamCompleted = new AtomicBoolean(false);
      final AtomicBoolean drainingExtProcStream = new AtomicBoolean(false);

      protected ExtProcClientCall(ClientCall<InputStream, InputStream> delegate,
          ExternalProcessorGrpc.ExternalProcessorStub stub,
          ExternalProcessor config) {
        super(delegate);
        this.stub = stub;
        this.config = config;
      }

      @Override
      public void start(Listener<InputStream> responseListener, Metadata headers) {
        this.requestHeaders = headers;
        this.wrappedListener = new ExtProcListener(responseListener, delegate(), this);

        extProcClientCallRequestObserver = (ClientCallStreamObserver<ProcessingRequest>) stub.process(new io.grpc.stub.StreamObserver<ProcessingResponse>() {
          @Override
          public void onNext(io.envoyproxy.envoy.service.ext_proc.v3.ProcessingResponse response) {
            if (response.hasImmediateResponse()) {
              handleImmediateResponse(response.getImmediateResponse(), responseListener);
              return;
            }

            if (config.getObservabilityMode()) {
              return;
            }

            if (response.getRequestDrain()) {
              drainingExtProcStream.set(true);
              synchronized (lock) {
                extProcClientCallRequestObserver.onCompleted(); // Sends half-close to ext_proc
              }
              return;
            }

            // --- Handlers for 6 Event types ---

            // 1. Client Headers
            if (response.hasRequestHeaders()) {
              if (response.getRequestHeaders().hasResponse()) {
                applyHeaderMutations(requestHeaders, response.getRequestHeaders().getResponse().getHeaderMutation());
              }
              headersSent = true;
              delegate().start(wrappedListener, requestHeaders);
              drainQueue();
            }
            // 2. Client Message (Request Body)
            else if (response.hasRequestBody()) {
              if (response.getRequestBody().hasResponse()
                  && response.getRequestBody().getResponse().hasBodyMutation()
                  && response.getRequestBody().getResponse().getBodyMutation().hasStreamedResponse()
                  && response.getRequestBody().getResponse().getBodyMutation().getStreamedResponse().getGrpcMessageCompressed()) {
                io.grpc.StatusRuntimeException ex = io.grpc.Status.INTERNAL
                    .withDescription("gRPC message compression not supported in ext_proc")
                    .asRuntimeException();
                synchronized (lock) {
                  extProcClientCallRequestObserver.onError(ex);
                }
                onError(ex);
                return;
              }
              handleRequestBodyResponse(response.getRequestBody());
            }
            // 3. We don't send request trailers in gRPC for half close.
            // 4. Server Headers
            else if (response.hasResponseHeaders()) {
              if (response.hasResponseHeaders() && response.getResponseHeaders().hasResponse()) {
                applyHeaderMutations(wrappedListener.savedHeaders, response.getResponseHeaders().getResponse().getHeaderMutation());
              }
              wrappedListener.proceedWithHeaders();
            }
            // 5. Server Message (Response Body)
            else if (response.hasResponseBody()) {
              if (response.hasResponseBody().hasResponse()
                  && response.getResponseBody().getResponse().hasBodyMutation()
                  && response.getResponseBody().getResponse().getBodyMutation().hasStreamedResponse()
                  && response.getResponseBody().getResponse().getBodyMutation().getStreamedResponse().getGrpcMessageCompressed()) {
                io.grpc.StatusRuntimeException ex = io.grpc.Status.INTERNAL
                    .withDescription("gRPC message compression not supported in ext_proc")
                    .asRuntimeException();
                synchronized (lock) {
                  extProcClientCallRequestObserver.onError(ex);
                }
                onError(ex);
                return;
              }
              handleResponseBodyResponse(response.getResponseBody(), wrappedListener);
            }
            // 6. Response Trailers Handshake Result
            if (response.hasResponseTrailers()) {
              // Use header_mutation directly from the TrailersResponse message
              if (response.getResponseTrailers().hasHeaderMutation()) {
                applyHeaderMutations(
                    wrappedListener.savedTrailers,
                    response.getResponseTrailers().getHeaderMutation()
                );
              }
              // Finally notify the local app of the completion
              wrappedListener.proceedWithClose();
              synchronized (lock) {
                extProcClientCallRequestObserver.onCompleted();
              }
            }
          }

          @Override
          public void onError(Throwable t) {
            if (config.getFailureModeAllow()) {
              handleFailOpen(wrappedListener);
            } else {
              if (extProcStreamFailed.compareAndSet(false, true)) {
                delegate().cancel("External processor stream failed", t);
              }
            }
          }

          @Override
          public void onCompleted() {
            drainingExtProcStream.set(false); // Reset draining flag
            handleFailOpen(wrappedListener);
          }
        });

        if (config.getObservabilityMode()) {
          extProcClientCallRequestObserver.setOnReadyHandler(this::onExtProcStreamReady);
        }

        wrappedListener.setStream(extProcClientCallRequestObserver);

        synchronized (lock) {
          extProcClientCallRequestObserver.onNext(io.envoyproxy.envoy.service.ext_proc.v3.ProcessingRequest.newBuilder()
              .setRequestHeaders(io.envoyproxy.envoy.service.ext_proc.v3.HttpHeaders.newBuilder()
                  .setHeaders(toHeaderMap(headers))
                  .build())
              .build());
        }

        if (config.getObservabilityMode()) {
          headersSent = true;
          delegate().start(wrappedListener, headers);
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
        if (drainingExtProcStream.get()) { // If draining, apply backpressure
          return false;
        }
        if (config.getObservabilityMode()) {
          synchronized (lock) {
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

        if (!headersSent && !config.getObservabilityMode()) {
          // If headers haven't been cleared by ext_proc yet, buffer the whole action
          try {
            byte[] bodyBytes = ByteStreams.toByteArray(message);
            pendingActions.add(() -> sendMessage(new ByteArrayInputStream(bodyBytes)));
          } catch (IOException e) {
            delegate().cancel("Failed to read message", e);
          }
          return;
        }

        try {
          byte[] bodyBytes = ByteStreams.toByteArray(message);
          synchronized (lock) {
            extProcClientCallRequestObserver.onNext(io.envoyproxy.envoy.service.ext_proc.v3.ProcessingRequest.newBuilder()
                .setRequestBody(io.envoyproxy.envoy.service.ext_proc.v3.HttpBody.newBuilder()
                    .setBody(com.google.protobuf.ByteString.copyFrom(bodyBytes))
                    .setEndOfStream(false)
                    .build())
                .build());
          }

          if (config.getObservabilityMode()) {
            super.sendMessage(new ByteArrayInputStream(bodyBytes));
          }
        } catch (IOException e) {
          delegate().cancel("Failed to serialize message for External Processor", e);
        }
      }

      @Override
      public void halfClose() {
        if (extProcStreamCompleted.get()) {
          super.halfClose();
          return;
        }

        // Signal end of request body stream to the external processor.
        synchronized (lock) {
          extProcClientCallRequestObserver.onNext(io.envoyproxy.envoy.service.ext_proc.v3.ProcessingRequest.newBuilder()
              .setRequestBody(io.envoyproxy.envoy.service.ext_proc.v3.HttpBody.newBuilder()
                  .setEndOfStreamWithoutMessage(true)
                  .build())
              .build());
        }
        super.halfClose();
      }

      @Override
      public void cancel(@Nullable String message, @Nullable Throwable cause) {
        synchronized (lock) {
          if (extProcClientCallRequestObserver != null) {
            extProcClientCallRequestObserver.onError(Status.CANCELLED.withDescription(message).withCause(cause).asRuntimeException());
          }
        }
        super.cancel(message, cause);
      }

      private void handleRequestBodyResponse(io.envoyproxy.envoy.service.ext_proc.v3.BodyResponse bodyResponse) {
        if (bodyResponse.hasResponse() && bodyResponse.getResponse().hasBodyMutation()) {
          io.envoyproxy.envoy.service.ext_proc.v3.BodyMutation mutation = bodyResponse.getResponse().getBodyMutation();
          if (mutation.hasBody() && !mutation.getBody().isEmpty()) { // Only send if body is not empty
            byte[] mutatedBody = mutation.getBody().toByteArray();
            super.sendMessage(new ByteArrayInputStream(mutatedBody));
          } else if (mutation.getClearBody()) {
            super.sendMessage(new ByteArrayInputStream(new byte[0]));
          }
          // If body mutation is present but has no body and clear_body is false, do nothing.
          // This means the processor chose to drop the message.
        }
        // If no response is present, the processor chose to drop the message.
      }

      private void handleResponseBodyResponse(io.envoyproxy.envoy.service.ext_proc.v3.BodyResponse bodyResponse, ExtProcListener listener) {
        if (bodyResponse.hasResponse() && bodyResponse.getResponse().hasBodyMutation()) {
          io.envoyproxy.envoy.service.ext_proc.v3.BodyMutation mutation = bodyResponse.getResponse().getBodyMutation();
          if (mutation.hasBody() && !mutation.getBody().isEmpty()) { // Only send if body is not empty
            listener.onExternalBody(mutation.getBody());
          } else if (mutation.getClearBody()) {
            listener.onExternalBody(com.google.protobuf.ByteString.EMPTY);
          }
        }
      }

      private void drainQueue() {
        Runnable action;
        while ((action = pendingActions.poll()) != null) action.run();
      }

      private void handleImmediateResponse(io.envoyproxy.envoy.service.ext_proc.v3.ImmediateResponse immediate, Listener<InputStream> listener) {
        io.grpc.Status status = io.grpc.Status.fromCodeValue(immediate.getGrpcStatus().getStatus());
        delegate().cancel("Rejected by ExtProc", null);
        listener.onClose(status, new Metadata());
        synchronized (lock) {
          extProcClientCallRequestObserver.onCompleted();
        }
      }

      private void handleFailOpen(ExtProcListener listener) {
        if (extProcStreamCompleted.compareAndSet(false, true)) {
          // The ext_proc stream is gone. "Fail open" means we proceed with the RPC
          // without any more processing.
          if (!headersSent) {
            headersSent = true;
            delegate().start(listener, requestHeaders);
            drainQueue();
          }
          listener.unblockAfterStreamComplete();
        }
      }
    }

    private static class ExtProcListener extends ForwardingClientCallListener.SimpleForwardingClientCallListener<InputStream> {
      private final ClientCall<?, ?> callDelegate; // The actual RPC call
      private final ExtProcClientCall extProcClientCall;
      private ClientCallStreamObserver<io.envoyproxy.envoy.service.ext_proc.v3.ProcessingRequest> stream;
      private Metadata savedHeaders;
      private Metadata savedTrailers;
      private io.grpc.Status savedStatus;

      protected ExtProcListener(ClientCall.Listener<InputStream> delegate, ClientCall<?, ?> callDelegate,
                                ExtProcClientCall extProcClientCall) {
        super(delegate);
        this.callDelegate = callDelegate;
        this.extProcClientCall = extProcClientCall;
      }

      void setStream(ClientCallStreamObserver<io.envoyproxy.envoy.service.ext_proc.v3.ProcessingRequest> stream) { this.stream = stream; }

      @Override
      public void onReady() {
        if (extProcClientCall.drainingExtProcStream.get()) { // Suppress onReady during drain
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
        synchronized (extProcClientCall.lock) {
          this.savedHeaders = headers;
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
        synchronized (extProcClientCall.lock) {
          if (savedHeaders != null) {
            super.onHeaders(savedHeaders);
            savedHeaders = null;
          }
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
           callDelegate.cancel("Failed to read server response", e);
        }
      }

      @Override
      public void onClose(io.grpc.Status status, Metadata trailers) {
        if (extProcClientCall.extProcStreamFailed.get()) {
          // The ext_proc stream died, which caused delegate().cancel() to be called, leading here.
          // The incoming status will be CANCELLED. We must not attempt to forward the server's
          // response trailers to the now-dead ext_proc stream. Instead, we close the
          // application's call with UNAVAILABLE as per the gRFC.
          super.onClose(Status.UNAVAILABLE.withDescription("External processor stream failed").withCause(status.getCause()), new Metadata());
          return;
        }
        if (extProcClientCall.extProcStreamCompleted.get()) {
          super.onClose(status, trailers);
          return;
        }

        synchronized (extProcClientCall.lock) {
          this.savedStatus = status;
          this.savedTrailers = trailers;

          // Signal end of response body stream to the external processor.
          sendResponseBodyToExtProc(null, true);

          // Event 6: Server Trailers with ACTUAL data
          extProcClientCall.extProcClientCallRequestObserver.onNext(ProcessingRequest.newBuilder()
              .setResponseTrailers(io.envoyproxy.envoy.service.ext_proc.v3.HttpTrailers.newBuilder()
                  .setTrailers(toHeaderMap(savedTrailers)) // Map the captured trailers here
                  .build())
              .build());
        }

        if (extProcClientCall.config.getObservabilityMode()) {
          super.onClose(status, trailers);
          synchronized (extProcClientCall.lock) {
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

        synchronized (extProcClientCall.lock) {
          extProcClientCall.extProcClientCallRequestObserver.onNext(ProcessingRequest.newBuilder()
              .setResponseBody(bodyBuilder.build())
              .build());
        }
      }

      /**
       * Called when ExtProc gives the final "OK" for the trailers phase.
       */
      void proceedWithClose() {
        synchronized (extProcClientCall.lock) {
          if (savedStatus != null) {
            super.onClose(savedStatus, savedTrailers);
            savedStatus = null;
            savedTrailers = null;
          }
        }
      }

      void onExternalBody(com.google.protobuf.ByteString body) {
         if (body.size() > 0) {
           super.onMessage(body.newInput());
         }
      }

      void unblockAfterStreamComplete() {
        // This is called when the ext_proc stream is gracefully completed.
        // We need to flush any pending state that is waiting for a response from ext_proc.
        proceedWithHeaders();
        proceedWithClose();
      }
    }
  }
}
