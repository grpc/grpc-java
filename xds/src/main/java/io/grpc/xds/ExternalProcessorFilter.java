package io.grpc.xds;

import static com.google.common.base.Preconditions.checkNotNull;

import com.google.common.annotations.VisibleForTesting;
import com.google.common.io.ByteStreams;
import com.google.protobuf.Any;
import com.google.protobuf.InvalidProtocolBufferException;
import com.google.protobuf.Message;
import io.envoyproxy.envoy.config.core.v3.GrpcService;
import io.envoyproxy.envoy.config.core.v3.HeaderValueOption;
import io.envoyproxy.envoy.extensions.filters.http.ext_proc.v3.ExternalProcessor;
import io.envoyproxy.envoy.extensions.filters.http.ext_proc.v3.ProcessingMode;
import io.envoyproxy.envoy.service.ext_proc.v3.ExternalProcessorGrpc;
import io.envoyproxy.envoy.service.ext_proc.v3.ProcessingRequest;
import io.envoyproxy.envoy.service.ext_proc.v3.ProcessingResponse;
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
import io.grpc.xds.internal.grpcservice.GrpcServiceChannelCreator;
import io.grpc.xds.internal.grpcservice.GrpcServiceChannelCreatorImpl;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.atomic.AtomicBoolean;
import javax.annotation.Nullable;

public class ExternalProcessorFilter implements Filter {
  static final String TYPE_URL = "type.googleapis.com/envoy.extensions.filters.http.ext_proc.v3.ExternalProcessor";

  final String filterInstanceName;
  // TODO: Make final after the need to replace with a mock from unit tests is removed.
  GrpcServiceChannelCreator grpcServiceChannelCreator;
  ManagedChannel grpcServiceChannel;
  ExternalProcessorGrpc.ExternalProcessorStub externalProcessorStub;
  private final Object lock = new Object();
  private GrpcService lastGrpcServiceConfig;

  public ExternalProcessorFilter(String name) {
    filterInstanceName = checkNotNull(name, "name");
    grpcServiceChannelCreator = new GrpcServiceChannelCreatorImpl();
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

      return ConfigOrError.fromConfig(new ExternalProcessorFilterConfig(externalProcessor));
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
    return new ExternalProcessorInterceptor(this, (ExternalProcessorFilterConfig) filterConfig, overrideConfig, scheduler);
  }

  ExternalProcessorGrpc.ExternalProcessorStub getExternalProcessorStub(ExternalProcessor config) {
    GrpcService newServiceConfig = config.getGrpcService();
    synchronized (lock) {
      // TODO: gRFC only mentions we should recreate channel if target or channel creds changed
      // but other fields in grpc service config also do seem relevant to warrant channel
      // recreation.
      if (grpcServiceChannel == null || !newServiceConfig.equals(lastGrpcServiceConfig)) {
        if (grpcServiceChannel != null) {
          // Shutdown the old channel if the config has changed
          grpcServiceChannel.shutdown();
        }
        grpcServiceChannel = grpcServiceChannelCreator.create(newServiceConfig);
        externalProcessorStub = ExternalProcessorGrpc.newStub(grpcServiceChannel);
        lastGrpcServiceConfig = newServiceConfig;
      }
      return externalProcessorStub;
    }
  }

  static final class ExternalProcessorFilterConfig implements FilterConfig {

    private final ExternalProcessor externalProcessor;

    ExternalProcessorFilterConfig(ExternalProcessor externalProcessor) {
      this.externalProcessor = externalProcessor;
    }

    @Override
    public String typeUrl() {
      return "type.googleapis.com/envoy.extensions.filters.http.ext_proc.v3.ExternalProcessor";
    }
  }

  static final class ExternalProcessorInterceptor implements ClientInterceptor {
    private final ExternalProcessorFilter filter;
    private final ExternalProcessorFilterConfig filterConfig;
    private final FilterConfig overrideConfig;
    private final ScheduledExecutorService scheduler;

    ExternalProcessorInterceptor(ExternalProcessorFilter filter,
        ExternalProcessorFilterConfig filterConfig,
        @Nullable FilterConfig overrideConfig, ScheduledExecutorService scheduler) {
      this.filter = filter;
      this.filterConfig = filterConfig;
      this.overrideConfig = overrideConfig;
      this.scheduler = scheduler;
    }

    @Override
    public <ReqT, RespT> ClientCall<ReqT, RespT> interceptCall(
        MethodDescriptor<ReqT, RespT> method,
        CallOptions callOptions,
        Channel next) {
      ExternalProcessorGrpc.ExternalProcessorStub stub = filter.getExternalProcessorStub(filterConfig.externalProcessor);
      ExternalProcessor config = filterConfig.externalProcessor;
      // Wrap the outgoing call to intercept client events
      return new ExtProcClientCall<>(next.newCall(method, callOptions), stub, method, config);
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
    private static class ExtProcClientCall<ReqT, RespT> extends SimpleForwardingClientCall<ReqT, RespT> {
      private final ExternalProcessorGrpc.ExternalProcessorStub stub;
      private final MethodDescriptor<ReqT, RespT> method;
      private final ExternalProcessor config;
      private ClientCallStreamObserver<ProcessingRequest> clientCallRequestObserver;
      private final Object extProcLock = new Object();
      private boolean extProcStreamReady;

      private boolean headersSent = false;
      private Metadata requestHeaders;
      private final java.util.Queue<Runnable> pendingActions = new java.util.concurrent.ConcurrentLinkedQueue<>();
      final AtomicBoolean extProcStreamFailed = new AtomicBoolean(false);
      final AtomicBoolean extProcStreamCompleted = new AtomicBoolean(false);

      protected ExtProcClientCall(ClientCall<ReqT, RespT> delegate,
          ExternalProcessorGrpc.ExternalProcessorStub stub,
          MethodDescriptor<ReqT, RespT> method,
          ExternalProcessor config) {
        super(delegate);
        this.stub = stub;
        this.method = method;
        this.config = config;
      }

      @Override
      public void start(Listener<RespT> responseListener, Metadata headers) {
        this.requestHeaders = headers;
        ExternalProcessorInterceptor.ExtProcListener<ReqT, RespT> wrappedListener = new ExternalProcessorInterceptor.ExtProcListener<>(responseListener, delegate(), method, this);

        clientCallRequestObserver = (ClientCallStreamObserver<ProcessingRequest>) stub.process(new io.grpc.stub.StreamObserver<ProcessingResponse>() {
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
              handleFailOpen(wrappedListener);
              clientCallRequestObserver.onCompleted();
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
                clientCallRequestObserver.onError(ex);
                onError(ex);
                return;
              }
              handleRequestBodyResponse(response.getRequestBody());
            }
            // 3. We don't send request trailers in gRPC for half close.
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
                  && response.getResponseBody().getResponse().hasBodyMutation()
                  && response.getResponseBody().getResponse().getBodyMutation().hasStreamedResponse()
                  && response.getResponseBody().getResponse().getBodyMutation().getStreamedResponse().getGrpcMessageCompressed()) {
                io.grpc.StatusRuntimeException ex = io.grpc.Status.INTERNAL
                    .withDescription("gRPC message compression not supported in ext_proc")
                    .asRuntimeException();
                clientCallRequestObserver.onError(ex);
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
            handleFailOpen(wrappedListener);
          }
        });

        if (config.getObservabilityMode()) {
          this.extProcStreamReady = clientCallRequestObserver.isReady();
          clientCallRequestObserver.setOnReadyHandler(this::onExtProcStreamReady);
        }

        wrappedListener.setStream(clientCallRequestObserver);

        sendToExtProc(io.envoyproxy.envoy.service.ext_proc.v3.ProcessingRequest.newBuilder()
            .setRequestHeaders(io.envoyproxy.envoy.service.ext_proc.v3.HttpHeaders.newBuilder()
                .setHeaders(toHeaderMap(headers))
                .build())
            .build());

        if (config.getObservabilityMode()) {
          headersSent = true;
          delegate().start(wrappedListener, headers);
        }
      }

      private void onExtProcStreamReady() {
        synchronized (extProcLock) {
          extProcStreamReady = true;
          extProcLock.notifyAll();
        }
      }

      private void sendToExtProc(ProcessingRequest request) {
        if (!config.getObservabilityMode()) {
          clientCallRequestObserver.onNext(request);
          return;
        }

        synchronized (extProcLock) {
          while (!extProcStreamReady) {
            try {
              extProcLock.wait();
            } catch (InterruptedException e) {
              Thread.currentThread().interrupt();
              delegate().cancel("Interrupted while waiting for ext_proc stream", e);
              return;
            }
          }
          clientCallRequestObserver.onNext(request);
          extProcStreamReady = clientCallRequestObserver.isReady();
        }
      }

      @Override
      public void sendMessage(ReqT message) {
        if (extProcStreamCompleted.get()) {
          super.sendMessage(message);
          return;
        }

        if (!headersSent && !config.getObservabilityMode()) {
          // If headers haven't been cleared by ext_proc yet, buffer the whole action
          pendingActions.add(() -> sendMessage(message));
          return;
        }

        try (InputStream is = method.streamRequest(message)) {
          byte[] bodyBytes = ByteStreams.toByteArray(is);
          sendToExtProc(io.envoyproxy.envoy.service.ext_proc.v3.ProcessingRequest.newBuilder()
              .setRequestBody(io.envoyproxy.envoy.service.ext_proc.v3.HttpBody.newBuilder()
                  .setBody(com.google.protobuf.ByteString.copyFrom(bodyBytes))
                  .setEndOfStream(false)
                  .build())
              .build());
        } catch (IOException e) {
          delegate().cancel("Failed to serialize message for External Processor", e);
        }

        if (config.getObservabilityMode()) {
          super.sendMessage(message);
        }
      }

      @Override
      public void halfClose() {
        if (extProcStreamCompleted.get()) {
          super.halfClose();
          return;
        }

        // Signal end of request body stream to the external processor.
        sendToExtProc(io.envoyproxy.envoy.service.ext_proc.v3.ProcessingRequest.newBuilder()
            .setRequestBody(io.envoyproxy.envoy.service.ext_proc.v3.HttpBody.newBuilder()
                .setEndOfStream(true)
                .build())
            .build());
        super.halfClose();
      }

      private void handleRequestBodyResponse(io.envoyproxy.envoy.service.ext_proc.v3.BodyResponse bodyResponse) {
        if (bodyResponse.hasResponse() && bodyResponse.getResponse().hasBodyMutation()) {
          io.envoyproxy.envoy.service.ext_proc.v3.BodyMutation mutation = bodyResponse.getResponse().getBodyMutation();
          if (mutation.hasBody()) {
            byte[] mutatedBody = mutation.getBody().toByteArray();
            try (InputStream is = new ByteArrayInputStream(mutatedBody)) {
              ReqT mutatedMessage = method.parseRequest(is);
              super.sendMessage(mutatedMessage);
            } catch (IOException e) {
              delegate().cancel("Failed to parse mutated message from External Processor", e);
            }
          } else if (mutation.getClearBody()) {
            // "clear_body" means we should send an empty message.
            try (InputStream is = new ByteArrayInputStream(new byte[0])) {
              ReqT emptyMessage = method.parseRequest(is);
              super.sendMessage(emptyMessage);
            } catch (IOException e) {
              // This should not happen with an empty stream.
              delegate().cancel("Failed to create empty message", e);
            }
          }
          // If body mutation is present but has no body and clear_body is false, do nothing.
          // This means the processor chose to drop the message.
        }
        // If no response is present, the processor chose to drop the message.
      }

      private void handleResponseBodyResponse(io.envoyproxy.envoy.service.ext_proc.v3.BodyResponse bodyResponse, ExternalProcessorInterceptor.ExtProcListener<ReqT, RespT> listener) {
        if (bodyResponse.hasResponse() && bodyResponse.getResponse().hasBodyMutation()) {
          io.envoyproxy.envoy.service.ext_proc.v3.BodyMutation mutation = bodyResponse.getResponse().getBodyMutation();
          if (mutation.hasBody()) {
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

      private void handleImmediateResponse(io.envoyproxy.envoy.service.ext_proc.v3.ImmediateResponse immediate, Listener<RespT> listener) {
        io.grpc.Status status = io.grpc.Status.fromCodeValue(immediate.getGrpcStatus().getStatus());
        delegate().cancel("Rejected by ExtProc", null);
        listener.onClose(status, new Metadata());
        clientCallRequestObserver.onCompleted();
      }

      private void handleFailOpen(ExtProcListener<ReqT, RespT> listener) {
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

    private static class ExtProcListener<ReqT, RespT> extends ForwardingClientCallListener.SimpleForwardingClientCallListener<RespT> {
      private final MethodDescriptor<?, RespT> method;
      private final ClientCall<?, RespT> callDelegate; // The actual RPC call
      private final ExtProcClientCall<ReqT, RespT> call;
      private ClientCallStreamObserver<io.envoyproxy.envoy.service.ext_proc.v3.ProcessingRequest> stream;
      private Metadata savedHeaders;
      private Metadata savedTrailers;
      private io.grpc.Status savedStatus;

      protected ExtProcListener(ClientCall.Listener<RespT> delegate, ClientCall<?, RespT> callDelegate,
                                MethodDescriptor<?, RespT> method, ExtProcClientCall<ReqT, RespT> call) {
        super(delegate);
        this.method = method;
        this.callDelegate = callDelegate;
        this.call = call;
      }

      void setStream(ClientCallStreamObserver<io.envoyproxy.envoy.service.ext_proc.v3.ProcessingRequest> stream) { this.stream = stream; }

      @Override
      public void onHeaders(Metadata headers) {
        if (call.extProcStreamCompleted.get()) {
          super.onHeaders(headers);
          return;
        }
        this.savedHeaders = headers;
        call.sendToExtProc(ProcessingRequest.newBuilder()
            .setResponseHeaders(io.envoyproxy.envoy.service.ext_proc.v3.HttpHeaders.newBuilder()
                .setHeaders(toHeaderMap(headers))
                .build())
            .build());

        if (call.config.getObservabilityMode()) {
          super.onHeaders(headers);
        }
      }

      void proceedWithHeaders() { super.onHeaders(savedHeaders); }

      @Override
      public void onMessage(RespT message) {
        if (call.extProcStreamCompleted.get()) {
          super.onMessage(message);
          return;
        }
        sendResponseBodyToExtProc(message, false);
        
        if (call.config.getObservabilityMode()) {
          super.onMessage(message);
        }
      }

      @Override
      public void onClose(io.grpc.Status status, Metadata trailers) {
        if (call.extProcStreamFailed.get()) {
          // The ext_proc stream died, which caused delegate().cancel() to be called, leading here.
          // The incoming status will be CANCELLED. We must not attempt to forward the server's
          // response trailers to the now-dead ext_proc stream. Instead, we close the
          // application's call with UNAVAILABLE as per the gRFC.
          super.onClose(Status.UNAVAILABLE.withDescription("External processor stream failed").withCause(status.getCause()), new Metadata());
          return;
        }
        if (call.extProcStreamCompleted.get()) {
          super.onClose(status, trailers);
          return;
        }

        this.savedStatus = status;
        this.savedTrailers = trailers;

        // Signal end of response body stream to the external processor.
        sendResponseBodyToExtProc(null, true);

        // Event 6: Server Trailers with ACTUAL data
        call.sendToExtProc(io.envoyproxy.envoy.service.ext_proc.v3.ProcessingRequest.newBuilder()
            .setResponseTrailers(io.envoyproxy.envoy.service.ext_proc.v3.HttpTrailers.newBuilder()
                .setTrailers(toHeaderMap(savedTrailers)) // Map the captured trailers here
                .build())
            .build());

        if (call.config.getObservabilityMode()) {
          super.onClose(status, trailers);
        }
      }

      private void sendResponseBodyToExtProc(@Nullable RespT message, boolean endOfStream) {
        if (call.extProcStreamCompleted.get()) {
          return;
        }
        try {
          io.envoyproxy.envoy.service.ext_proc.v3.HttpBody.Builder bodyBuilder =
              io.envoyproxy.envoy.service.ext_proc.v3.HttpBody.newBuilder();
          if (message != null) {
            try (java.io.InputStream is = method.streamResponse(message)) {
              byte[] bodyBytes = ByteStreams.toByteArray(is);
              bodyBuilder.setBody(com.google.protobuf.ByteString.copyFrom(bodyBytes));
            }
          }
          bodyBuilder.setEndOfStream(endOfStream);

          call.sendToExtProc(io.envoyproxy.envoy.service.ext_proc.v3.ProcessingRequest.newBuilder()
              .setResponseBody(bodyBuilder.build())
              .build());

        } catch (java.io.IOException e) {
          // 1. Notify the external processor stream of the failure
          stream.onError(io.grpc.Status.INTERNAL
              .withDescription("Failed to serialize server response for ExtProc")
              .withCause(e)
              .asRuntimeException());

          // 2. Kill the RPC toward the remote service
          // This tells the transport to stop receiving/sending data immediately.
          callDelegate.cancel("Serialization error in interceptor", e);

          // 3. Notify the local application
          // This triggers the client's StreamObserver.onError()
          super.onClose(io.grpc.Status.INTERNAL.withDescription("Failed to process server response"), new Metadata());
        }
      }

      /**
       * Called when ExtProc gives the final "OK" for the trailers phase.
       */
      void proceedWithClose() {
        super.onClose(savedStatus, savedTrailers);
      }

      void onExternalBody(com.google.protobuf.ByteString body) {
        try (InputStream is = body.newInput()) {
           RespT message = method.parseResponse(is);
           super.onMessage(message);
        } catch (Exception e) {
           // This will happen if the ext_proc server sends invalid protobuf data.
           // We should probably fail the call.
           super.onClose(Status.INTERNAL.withDescription("Failed to parse response from ext_proc").withCause(e), new Metadata());
        }
      }

      void unblockAfterStreamComplete() {
        // This is called when the ext_proc stream is gracefully completed.
        // We need to flush any pending state that is waiting for a response from ext_proc.
        if (savedHeaders != null) {
          proceedWithHeaders();
        }
        // No message queue to flush anymore.
        if (savedStatus != null) {
          proceedWithClose();
        }
      }
    }
  }
}
