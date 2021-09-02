/*
 * Copyright 2014 The gRPC Authors
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

package io.grpc.stub;

import static com.google.common.base.Preconditions.checkNotNull;
import static com.google.common.base.Preconditions.checkState;

import com.google.common.annotations.VisibleForTesting;
import com.google.common.base.Preconditions;
import io.grpc.Metadata;
import io.grpc.MethodDescriptor;
import io.grpc.ServerCall;
import io.grpc.ServerCallHandler;
import io.grpc.Status;

/**
 * Utility functions for adapting {@link ServerCallHandler}s to application service implementation,
 * meant to be used by the generated code.
 */
public final class ServerCalls {

  @VisibleForTesting
  static final String TOO_MANY_REQUESTS = "Too many requests";
  @VisibleForTesting
  static final String MISSING_REQUEST = "Half-closed without a request";

  private ServerCalls() {
  }

  /**
   * Creates a {@link ServerCallHandler} for a unary call method of the service.
   *
   * @param method an adaptor to the actual method on the service implementation.
   */
  public static <ReqT, RespT> ServerCallHandler<ReqT, RespT> asyncUnaryCall(
      UnaryMethod<ReqT, RespT> method) {
    return new UnaryServerCallHandler<>(method, false);
  }

  /**
   * Creates a {@link ServerCallHandler} for a server streaming method of the service.
   *
   * @param method an adaptor to the actual method on the service implementation.
   */
  public static <ReqT, RespT> ServerCallHandler<ReqT, RespT> asyncServerStreamingCall(
      ServerStreamingMethod<ReqT, RespT> method) {
    return new UnaryServerCallHandler<>(method, true);
  }

  /**
   * Creates a {@link ServerCallHandler} for a client streaming method of the service.
   *
   * @param method an adaptor to the actual method on the service implementation.
   */
  public static <ReqT, RespT> ServerCallHandler<ReqT, RespT> asyncClientStreamingCall(
      ClientStreamingMethod<ReqT, RespT> method) {
    return new StreamingServerCallHandler<>(method, false);
  }

  /**
   * Creates a {@link ServerCallHandler} for a bidi streaming method of the service.
   *
   * @param method an adaptor to the actual method on the service implementation.
   */
  public static <ReqT, RespT> ServerCallHandler<ReqT, RespT> asyncBidiStreamingCall(
      BidiStreamingMethod<ReqT, RespT> method) {
    return new StreamingServerCallHandler<>(method, true);
  }

  /**
   * Adaptor to a unary call method.
   */
  public interface UnaryMethod<ReqT, RespT> extends UnaryRequestMethod<ReqT, RespT> {
    @Override void invoke(ReqT request, StreamObserver<RespT> responseObserver);
  }

  /**
   * Adaptor to a server streaming method.
   */
  public interface ServerStreamingMethod<ReqT, RespT> extends UnaryRequestMethod<ReqT, RespT> {
    @Override void invoke(ReqT request, StreamObserver<RespT> responseObserver);
  }

  /**
   * Adaptor to a client streaming method.
   */
  public interface ClientStreamingMethod<ReqT, RespT> extends StreamingRequestMethod<ReqT, RespT> {
    @Override StreamObserver<ReqT> invoke(StreamObserver<RespT> responseObserver);
  }

  /**
   * Adaptor to a bidirectional streaming method.
   */
  public interface BidiStreamingMethod<ReqT, RespT> extends StreamingRequestMethod<ReqT, RespT> {
    @Override StreamObserver<ReqT> invoke(StreamObserver<RespT> responseObserver);
  }

  private static final class UnaryServerCallHandler<ReqT, RespT>
      implements ServerCallHandler<ReqT, RespT> {

    private final UnaryRequestMethod<ReqT, RespT> method;
    private final boolean serverStreaming;

    // Non private to avoid synthetic class
    UnaryServerCallHandler(UnaryRequestMethod<ReqT, RespT> method, boolean serverStreaming) {
      this.method = method;
      this.serverStreaming = serverStreaming;
    }

    @Override
    public ServerCall.Listener<ReqT> startCall(ServerCall<ReqT, RespT> call, Metadata headers) {
      Preconditions.checkArgument(
          call.getMethodDescriptor().getType().clientSendsOneMessage(),
          "asyncUnaryRequestCall is only for clientSendsOneMessage methods");
      ServerCallStreamObserverImpl<ReqT, RespT> responseObserver =
          new ServerCallStreamObserverImpl<>(call, serverStreaming);
      // We expect only 1 request, but we ask for 2 requests here so that if a misbehaving client
      // sends more than 1 requests, ServerCall will catch it. Note that disabling auto
      // inbound flow control has no effect on unary calls.
      call.request(2);
      return new UnaryServerCallListener(responseObserver, call);
    }

    private final class UnaryServerCallListener extends ServerCall.Listener<ReqT> {
      private final ServerCall<ReqT, RespT> call;
      private final ServerCallStreamObserverImpl<ReqT, RespT> responseObserver;
      private boolean canInvoke = true;
      private boolean wasReady;
      private ReqT request;

      // Non private to avoid synthetic class
      UnaryServerCallListener(
          ServerCallStreamObserverImpl<ReqT, RespT> responseObserver,
          ServerCall<ReqT, RespT> call) {
        this.call = call;
        this.responseObserver = responseObserver;
      }

      @Override
      public void onMessage(ReqT request) {
        if (this.request != null) {
          // Safe to close the call, because the application has not yet been invoked
          call.close(
              Status.INTERNAL.withDescription(TOO_MANY_REQUESTS),
              new Metadata());
          canInvoke = false;
          return;
        }

        // We delay calling method.invoke() until onHalfClose() to make sure the client
        // half-closes.
        this.request = request;
      }

      @Override
      public void onHalfClose() {
        if (!canInvoke) {
          return;
        }
        if (request == null) {
          // Safe to close the call, because the application has not yet been invoked
          call.close(
              Status.INTERNAL.withDescription(MISSING_REQUEST),
              new Metadata());
          return;
        }

        method.invoke(request, responseObserver);
        request = null;
        responseObserver.freeze();
        if (wasReady) {
          // Since we are calling invoke in halfClose we have missed the onReady
          // event from the transport so recover it here.
          onReady();
        }
      }

      @Override
      public void onCancel() {
        if (responseObserver.onCancelHandler != null) {
          responseObserver.onCancelHandler.run();
        } else {
          // Only trigger exceptions if unable to provide notification via a callback
          responseObserver.cancelled = true;
        }
      }

      @Override
      public void onReady() {
        wasReady = true;
        if (responseObserver.onReadyHandler != null) {
          responseObserver.onReadyHandler.run();
        }
      }
    }
  }

  private static final class StreamingServerCallHandler<ReqT, RespT>
      implements ServerCallHandler<ReqT, RespT> {

    private final StreamingRequestMethod<ReqT, RespT> method;
    private final boolean bidi;

    // Non private to avoid synthetic class
    StreamingServerCallHandler(StreamingRequestMethod<ReqT, RespT> method, boolean bidi) {
      this.method = method;
      this.bidi = bidi;
    }

    @Override
    public ServerCall.Listener<ReqT> startCall(ServerCall<ReqT, RespT> call, Metadata headers) {
      ServerCallStreamObserverImpl<ReqT, RespT> responseObserver =
          new ServerCallStreamObserverImpl<>(call, bidi);
      StreamObserver<ReqT> requestObserver = method.invoke(responseObserver);
      responseObserver.freeze();
      if (responseObserver.autoRequestEnabled) {
        call.request(1);
      }
      return new StreamingServerCallListener(requestObserver, responseObserver, call);
    }

    private final class StreamingServerCallListener extends ServerCall.Listener<ReqT> {

      private final StreamObserver<ReqT> requestObserver;
      private final ServerCallStreamObserverImpl<ReqT, RespT> responseObserver;
      private final ServerCall<ReqT, RespT> call;
      private boolean halfClosed = false;

      // Non private to avoid synthetic class
      StreamingServerCallListener(
          StreamObserver<ReqT> requestObserver,
          ServerCallStreamObserverImpl<ReqT, RespT> responseObserver,
          ServerCall<ReqT, RespT> call) {
        this.requestObserver = requestObserver;
        this.responseObserver = responseObserver;
        this.call = call;
      }

      @Override
      public void onMessage(ReqT request) {
        requestObserver.onNext(request);

        // Request delivery of the next inbound message.
        if (responseObserver.autoRequestEnabled) {
          call.request(1);
        }
      }

      @Override
      public void onHalfClose() {
        halfClosed = true;
        requestObserver.onCompleted();
      }

      @Override
      public void onCancel() {
        if (responseObserver.onCancelHandler != null) {
          responseObserver.onCancelHandler.run();
        } else {
          // Only trigger exceptions if unable to provide notification via a callback. Even though
          // onError would provide notification to the server, we still throw an error since there
          // isn't a guaranteed callback available. If the cancellation happened in a different
          // order the service could be surprised to see the exception.
          responseObserver.cancelled = true;
        }
        if (!halfClosed) {
          requestObserver.onError(
              Status.CANCELLED
                  .withDescription("client cancelled")
                  .asRuntimeException());
        }
      }

      @Override
      public void onReady() {
        if (responseObserver.onReadyHandler != null) {
          responseObserver.onReadyHandler.run();
        }
      }
    }
  }

  private interface UnaryRequestMethod<ReqT, RespT> {
    /**
     * The provided {@code responseObserver} will extend {@link ServerCallStreamObserver}.
     */
    void invoke(ReqT request, StreamObserver<RespT> responseObserver);
  }

  private interface StreamingRequestMethod<ReqT, RespT> {
    /**
     * The provided {@code responseObserver} will extend {@link ServerCallStreamObserver}.
     */
    StreamObserver<ReqT> invoke(StreamObserver<RespT> responseObserver);
  }

  private static final class ServerCallStreamObserverImpl<ReqT, RespT>
      extends ServerCallStreamObserver<RespT> {
    final ServerCall<ReqT, RespT> call;
    private final boolean serverStreamingOrBidi;
    volatile boolean cancelled;
    private boolean frozen;
    private boolean autoRequestEnabled = true;
    private boolean sentHeaders;
    private Runnable onReadyHandler;
    private Runnable onCancelHandler;
    private boolean aborted = false;
    private boolean completed = false;

    // Non private to avoid synthetic class
    ServerCallStreamObserverImpl(ServerCall<ReqT, RespT> call, boolean serverStreamingOrBidi) {
      this.call = call;
      this.serverStreamingOrBidi = serverStreamingOrBidi;
    }

    private void freeze() {
      this.frozen = true;
    }

    @Override
    public void setMessageCompression(boolean enable) {
      call.setMessageCompression(enable);
    }

    @Override
    public void setCompression(String compression) {
      call.setCompression(compression);
    }

    @Override
    public void onNext(RespT response) {
      if (cancelled) {
        if (serverStreamingOrBidi) {
          throw Status.CANCELLED
              .withDescription("call already cancelled. "
                  + "Use ServerCallStreamObserver.setOnCancelHandler() to disable this exception")
              .asRuntimeException();
        } else {
          // We choose not to throw for unary responses. The exception is intended to stop servers
          // from continuing processing, but for unary responses there is no further processing
          // so throwing an exception would not provide a benefit and would increase application
          // complexity.
        }
      }
      checkState(!aborted, "Stream was terminated by error, no further calls are allowed");
      checkState(!completed, "Stream is already completed, no further calls are allowed");
      if (!sentHeaders) {
        call.sendHeaders(new Metadata());
        sentHeaders = true;
      }
      call.sendMessage(response);
    }

    @Override
    public void onError(Throwable t) {
      Metadata metadata = Status.trailersFromThrowable(t);
      if (metadata == null) {
        metadata = new Metadata();
      }
      call.close(Status.fromThrowable(t), metadata);
      aborted = true;
    }

    @Override
    public void onCompleted() {
      call.close(Status.OK, new Metadata());
      completed = true;
    }

    @Override
    public boolean isReady() {
      return call.isReady();
    }

    @Override
    public void setOnReadyHandler(Runnable r) {
      checkState(!frozen, "Cannot alter onReadyHandler after initialization. May only be called "
          + "during the initial call to the application, before the service returns its "
          + "StreamObserver");
      this.onReadyHandler = r;
    }

    @Override
    public boolean isCancelled() {
      return call.isCancelled();
    }

    @Override
    public void setOnCancelHandler(Runnable onCancelHandler) {
      checkState(!frozen, "Cannot alter onCancelHandler after initialization. May only be called "
          + "during the initial call to the application, before the service returns its "
          + "StreamObserver");
      this.onCancelHandler = onCancelHandler;
    }

    @Deprecated
    @Override
    public void disableAutoInboundFlowControl() {
      disableAutoRequest();
    }

    @Override
    public void disableAutoRequest() {
      checkState(!frozen, "Cannot disable auto flow control after initialization");
      autoRequestEnabled = false;
    }

    @Override
    public void request(int count) {
      call.request(count);
    }
  }

  /**
   * Sets unimplemented status for method on given response stream for unary call.
   *
   * @param methodDescriptor of method for which error will be thrown.
   * @param responseObserver on which error will be set.
   */
  public static void asyncUnimplementedUnaryCall(
      MethodDescriptor<?, ?> methodDescriptor, StreamObserver<?> responseObserver) {
    checkNotNull(methodDescriptor, "methodDescriptor");
    checkNotNull(responseObserver, "responseObserver");
    responseObserver.onError(Status.UNIMPLEMENTED
        .withDescription(String.format("Method %s is unimplemented",
            methodDescriptor.getFullMethodName()))
        .asRuntimeException());
  }

  /**
   * Sets unimplemented status for streaming call.
   *
   * @param methodDescriptor of method for which error will be thrown.
   * @param responseObserver on which error will be set.
   */
  public static <T> StreamObserver<T> asyncUnimplementedStreamingCall(
      MethodDescriptor<?, ?> methodDescriptor, StreamObserver<?> responseObserver) {
    // NB: For streaming call we want to do the same as for unary call. Fail-fast by setting error
    // on responseObserver and then return no-op observer.
    asyncUnimplementedUnaryCall(methodDescriptor, responseObserver);
    return new NoopStreamObserver<>();
  }

  /**
   * No-op implementation of StreamObserver. Used in abstract stubs for default implementations of
   * methods which throws UNIMPLEMENTED error and tests.
   */
  static class NoopStreamObserver<V> implements StreamObserver<V> {
    @Override
    public void onNext(V value) {
    }

    @Override
    public void onError(Throwable t) {
    }

    @Override
    public void onCompleted() {
    }
  }
}
