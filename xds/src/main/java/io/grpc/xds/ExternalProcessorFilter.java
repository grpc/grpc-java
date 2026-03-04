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
import io.envoyproxy.envoy.service.ext_proc.v3.ExternalProcessorGrpc;
import io.envoyproxy.envoy.service.ext_proc.v3.ProcessingRequest;
import io.grpc.CallOptions;
import io.grpc.Channel;
import io.grpc.ClientCall;
import io.grpc.ClientInterceptor;

import io.grpc.ForwardingClientCall.SimpleForwardingClientCall;
import io.grpc.ManagedChannel;
import io.grpc.Metadata;
import io.grpc.MethodDescriptor;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import javax.annotation.Nullable;
import java.util.concurrent.ScheduledExecutorService;

public class ExternalProcessorFilter implements Filter {
  static final String TYPE_URL = "type.googleapis.com/envoy.extensions.filters.http.ext_proc.v3.ExternalProcessor";

  ManagedChannel extProcChannel;
  final String filterInstanceName;
  public ExternalProcessorFilter(String name) {
    filterInstanceName = checkNotNull(name, "name");
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
    return new ExternalProcessorInterceptor((ExternalProcessorFilterConfig) filterConfig, overrideConfig, scheduler);
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
    private final ExternalProcessorFilterConfig filterConfig;
    private final FilterConfig overrideConfig;
    private final ScheduledExecutorService scheduler;

    ExternalProcessorInterceptor(ExternalProcessorFilterConfig filterConfig,
                                 @Nullable FilterConfig overrideConfig, ScheduledExecutorService scheduler) {
      this.filterConfig = filterConfig;
      this.overrideConfig = overrideConfig;
      this.scheduler = scheduler;
    }

    @Override
    public <ReqT, RespT> ClientCall<ReqT, RespT> interceptCall(
        MethodDescriptor<ReqT, RespT> method,
        CallOptions callOptions,
        Channel next) {

      ExternalProcessorGrpc.ExternalProcessorStub stub = getExternalProcessorStub(filterConfig.externalProcessor.getGrpcService());

      // Wrap the outgoing call to intercept client events
      return new ExtProcClientCall<>(next.newCall(method, callOptions), stub, method);
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
      private io.grpc.stub.StreamObserver<io.envoyproxy.envoy.service.ext_proc.v3.ProcessingRequest> requestObserver;

      private boolean headersSent = false;
      private Metadata requestHeaders;
      private final java.util.Queue<Runnable> pendingActions = new java.util.concurrent.ConcurrentLinkedQueue<>();

      protected ExtProcClientCall(ClientCall<ReqT, RespT> delegate,
                                  ExternalProcessorGrpc.ExternalProcessorStub stub,
                                  MethodDescriptor<ReqT, RespT> method) {
        super(delegate);
        this.stub = stub;
        this.method = method;
      }

      @Override
      public void start(Listener<RespT> responseListener, Metadata headers) {
        this.requestHeaders = headers;
        ExternalProcessorInterceptor.ExtProcListener<RespT> wrappedListener = new ExternalProcessorInterceptor.ExtProcListener<>(responseListener, delegate(), method);

        requestObserver = stub.process(new io.grpc.stub.StreamObserver<io.envoyproxy.envoy.service.ext_proc.v3.ProcessingResponse>() {
          @Override
          public void onNext(io.envoyproxy.envoy.service.ext_proc.v3.ProcessingResponse response) {
            if (response.hasImmediateResponse()) {
              handleImmediateResponse(response.getImmediateResponse(), responseListener);
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

          @Override public void onError(Throwable t) { delegate().cancel("ExtProc failed", t); }
          @Override public void onCompleted() {}
        });

        wrappedListener.setStream(requestObserver);

        requestObserver.onNext(io.envoyproxy.envoy.service.ext_proc.v3.ProcessingRequest.newBuilder()
            .setRequestHeaders(io.envoyproxy.envoy.service.ext_proc.v3.HttpHeaders.newBuilder()
                .setHeaders(toHeaderMap(headers))
                .build())
            .build());
      }

      @Override
      public void sendMessage(ReqT message) {
        if (!headersSent) {
          // If headers haven't been cleared by ext_proc yet, buffer the whole action
          pendingActions.add(() -> sendMessage(message));
          return;
        }

        try (InputStream is = method.streamRequest(message)) {
          // Correctly convert InputStream to byte array using Guava
          byte[] bodyBytes = ByteStreams.toByteArray(is);

          requestObserver.onNext(io.envoyproxy.envoy.service.ext_proc.v3.ProcessingRequest.newBuilder()
              .setRequestBody(io.envoyproxy.envoy.service.ext_proc.v3.HttpBody.newBuilder()
                  .setBody(com.google.protobuf.ByteString.copyFrom(bodyBytes))
                  .build())
              .build());
          // The external processor is now responsible for the message. We don't send it from here.
        } catch (IOException e) {
          delegate().cancel("Failed to serialize message for External Processor", e);
        }
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

      private void handleResponseBodyResponse(io.envoyproxy.envoy.service.ext_proc.v3.BodyResponse bodyResponse, ExternalProcessorInterceptor.ExtProcListener<RespT> listener) {
        // Pass the (potentially modified) message to the real listener
        listener.proceedWithNextMessage();
      }

      private void drainQueue() {
        Runnable action;
        while ((action = pendingActions.poll()) != null) action.run();
      }

      private void handleImmediateResponse(io.envoyproxy.envoy.service.ext_proc.v3.ImmediateResponse immediate, Listener<RespT> listener) {
        io.grpc.Status status = io.grpc.Status.fromCodeValue(immediate.getGrpcStatus().getStatus());
        delegate().cancel("Rejected by ExtProc", null);
        listener.onClose(status, new Metadata());
        requestObserver.onCompleted();
      }

      @Override
      public void halfClose() {
        // Event: Client Half-Close
        requestObserver.onNext(io.envoyproxy.envoy.service.ext_proc.v3.ProcessingRequest.newBuilder()
            .setRequestTrailers(io.envoyproxy.envoy.service.ext_proc.v3.HttpTrailers.newBuilder().build())
            .build());
        super.halfClose();
      }
    }

    private static class ExtProcListener<RespT> extends io.grpc.ForwardingClientCallListener.SimpleForwardingClientCallListener<RespT> {
      private final MethodDescriptor<?, RespT> method;
      private final ClientCall<?, RespT> callDelegate; // The actual RPC call
      private io.grpc.stub.StreamObserver<io.envoyproxy.envoy.service.ext_proc.v3.ProcessingRequest> stream;
      Metadata savedHeaders;
      Metadata savedTrailers;
      io.grpc.Status savedStatus;
      private final java.util.Queue<RespT> messageQueue = new java.util.concurrent.ConcurrentLinkedQueue<>();

      protected ExtProcListener(ClientCall.Listener<RespT> delegate, ClientCall<?, RespT> callDelegate, MethodDescriptor<?, RespT> method) {
        super(delegate);
        this.method = method;
        this.callDelegate = callDelegate;
      }

      void setStream(io.grpc.stub.StreamObserver<io.envoyproxy.envoy.service.ext_proc.v3.ProcessingRequest> stream) { this.stream = stream; }

      @Override
      public void onHeaders(Metadata headers) {
        this.savedHeaders = headers;
        stream.onNext(ProcessingRequest.newBuilder()
            .setResponseHeaders(io.envoyproxy.envoy.service.ext_proc.v3.HttpHeaders.newBuilder()
                .setHeaders(toHeaderMap(headers))
                .build())
            .build());
      }

      void proceedWithHeaders() { super.onHeaders(savedHeaders); }

      @Override
      public void onMessage(RespT message) {
        try (java.io.InputStream is = method.streamResponse(message)) {
          // Use Guava to convert the server's response message to bytes
          byte[] bodyBytes = ByteStreams.toByteArray(is);

          messageQueue.add(message);

          // Event 5: Server Message (Response Body) sent to Ext Proc
          stream.onNext(io.envoyproxy.envoy.service.ext_proc.v3.ProcessingRequest.newBuilder()
              .setResponseBody(io.envoyproxy.envoy.service.ext_proc.v3.HttpBody.newBuilder()
                  .setBody(com.google.protobuf.ByteString.copyFrom(bodyBytes))
                  .build())
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

      @Override
      public void onClose(io.grpc.Status status, Metadata trailers) {
        this.savedStatus = status;
        this.savedTrailers = trailers;

        // Event 6: Server Trailers with ACTUAL data
        stream.onNext(io.envoyproxy.envoy.service.ext_proc.v3.ProcessingRequest.newBuilder()
            .setResponseTrailers(io.envoyproxy.envoy.service.ext_proc.v3.HttpTrailers.newBuilder()
                .setTrailers(toHeaderMap(savedTrailers)) // Map the captured trailers here
                .build())
            .build());
      }

      /**
       * Called when ExtProc gives the final "OK" for the trailers phase.
       */
      void proceedWithClose() {
        super.onClose(savedStatus, savedTrailers);
      }

      void proceedWithNextMessage() {
        RespT msg = messageQueue.poll();
        if (msg != null) super.onMessage(msg);
      }
    }

    @VisibleForTesting
    ExternalProcessorGrpc.ExternalProcessorStub getExternalProcessorStub(GrpcService service) {
      return null; // Implementation needed
    }
  }
}
