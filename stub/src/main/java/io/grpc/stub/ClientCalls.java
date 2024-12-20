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
import com.google.common.base.MoreObjects;
import com.google.common.base.Preconditions;
import com.google.common.base.Strings;
import com.google.common.util.concurrent.AbstractFuture;
import com.google.common.util.concurrent.ListenableFuture;
import io.grpc.CallOptions;
import io.grpc.Channel;
import io.grpc.ClientCall;
import io.grpc.ExperimentalApi;
import io.grpc.Metadata;
import io.grpc.MethodDescriptor;
import io.grpc.Status;
import io.grpc.StatusException;
import io.grpc.StatusRuntimeException;
import java.util.Iterator;
import java.util.NoSuchElementException;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Executor;
import java.util.concurrent.Future;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.locks.Condition;
import java.util.concurrent.locks.Lock;
import java.util.concurrent.locks.LockSupport;
import java.util.concurrent.locks.ReentrantLock;
import java.util.function.Predicate;
import java.util.logging.Level;
import java.util.logging.Logger;
import javax.annotation.Nonnull;
import javax.annotation.Nullable;

/**
 * Utility functions for processing different call idioms. We have one-to-one correspondence
 * between utilities in this class and the potential signatures in a generated stub class so
 * that the runtime can vary behavior without requiring regeneration of the stub.
 */
public final class ClientCalls {

  private static final Logger logger = Logger.getLogger(ClientCalls.class.getName());

  @VisibleForTesting
  static boolean rejectRunnableOnExecutor =
      !Strings.isNullOrEmpty(System.getenv("GRPC_CLIENT_CALL_REJECT_RUNNABLE"))
          && Boolean.parseBoolean(System.getenv("GRPC_CLIENT_CALL_REJECT_RUNNABLE"));

  // Prevent instantiation
  private ClientCalls() {}

  /**
   * Executes a unary call with a response {@link StreamObserver}.  The {@code call} should not be
   * already started.  After calling this method, {@code call} should no longer be used.
   *
   * <p>If the provided {@code responseObserver} is an instance of {@link ClientResponseObserver},
   * {@code beforeStart()} will be called.
   */
  public static <ReqT, RespT> void asyncUnaryCall(
      ClientCall<ReqT, RespT> call, ReqT req, StreamObserver<RespT> responseObserver) {
    checkNotNull(responseObserver, "responseObserver");
    asyncUnaryRequestCall(call, req, responseObserver, false);
  }

  /**
   * Executes a server-streaming call with a response {@link StreamObserver}.  The {@code call}
   * should not be already started.  After calling this method, {@code call} should no longer be
   * used.
   *
   * <p>If the provided {@code responseObserver} is an instance of {@link ClientResponseObserver},
   * {@code beforeStart()} will be called.
   */
  public static <ReqT, RespT> void asyncServerStreamingCall(
      ClientCall<ReqT, RespT> call, ReqT req, StreamObserver<RespT> responseObserver) {
    checkNotNull(responseObserver, "responseObserver");
    asyncUnaryRequestCall(call, req, responseObserver, true);
  }

  /**
   * Executes a client-streaming call returning a {@link StreamObserver} for the request messages.
   * The {@code call} should not be already started.  After calling this method, {@code call}
   * should no longer be used.
   *
   * <p>If the provided {@code responseObserver} is an instance of {@link ClientResponseObserver},
   * {@code beforeStart()} will be called.
   *
   * @return request stream observer. It will extend {@link ClientCallStreamObserver}
   */
  public static <ReqT, RespT> StreamObserver<ReqT> asyncClientStreamingCall(
      ClientCall<ReqT, RespT> call,
      StreamObserver<RespT> responseObserver) {
    checkNotNull(responseObserver, "responseObserver");
    return asyncStreamingRequestCall(call, responseObserver, false);
  }

  /**
   * Executes a bidirectional-streaming call.  The {@code call} should not be already started.
   * After calling this method, {@code call} should no longer be used.
   *
   * <p>If the provided {@code responseObserver} is an instance of {@link ClientResponseObserver},
   * {@code beforeStart()} will be called.
   *
   * @return request stream observer. It will extend {@link ClientCallStreamObserver}
   */
  public static <ReqT, RespT> StreamObserver<ReqT> asyncBidiStreamingCall(
      ClientCall<ReqT, RespT> call, StreamObserver<RespT> responseObserver) {
    checkNotNull(responseObserver, "responseObserver");
    return asyncStreamingRequestCall(call, responseObserver, true);
  }

  /**
   * Executes a unary call and blocks on the response.  The {@code call} should not be already
   * started.  After calling this method, {@code call} should no longer be used.
   *
   * @return the single response message.
   * @throws StatusRuntimeException on error
   */
  public static <ReqT, RespT> RespT blockingUnaryCall(ClientCall<ReqT, RespT> call, ReqT req) {
    try {
      return getUnchecked(futureUnaryCall(call, req));
    } catch (RuntimeException | Error e) {
      throw cancelThrow(call, e);
    }
  }

  /**
   * Executes a unary call and blocks on the response.  The {@code call} should not be already
   * started.  After calling this method, {@code call} should no longer be used.
   *
   * @return the single response message.
   * @throws StatusRuntimeException on error
   */
  public static <ReqT, RespT> RespT blockingUnaryCall(
      Channel channel, MethodDescriptor<ReqT, RespT> method, CallOptions callOptions, ReqT req) {
    ThreadlessExecutor executor = new ThreadlessExecutor();
    boolean interrupt = false;
    ClientCall<ReqT, RespT> call = channel.newCall(method,
        callOptions.withOption(ClientCalls.STUB_TYPE_OPTION, StubType.BLOCKING)
            .withExecutor(executor));
    try {
      ListenableFuture<RespT> responseFuture = futureUnaryCall(call, req);
      while (!responseFuture.isDone()) {
        try {
          executor.waitAndDrain();
        } catch (InterruptedException e) {
          interrupt = true;
          call.cancel("Thread interrupted", e);
          // Now wait for onClose() to be called, so interceptors can clean up
        }
      }
      executor.shutdown();
      return getUnchecked(responseFuture);
    } catch (RuntimeException | Error e) {
      // Something very bad happened. All bets are off; it may be dangerous to wait for onClose().
      throw cancelThrow(call, e);
    } finally {
      if (interrupt) {
        Thread.currentThread().interrupt();
      }
    }
  }

  /**
   * Executes a server-streaming call returning a blocking {@link Iterator} over the
   * response stream.  The {@code call} should not be already started.  After calling this method,
   * {@code call} should no longer be used.
   *
   * <p>The returned iterator may throw {@link StatusRuntimeException} on error.
   *
   * @return an iterator over the response stream.
   */
  public static <ReqT, RespT> Iterator<RespT> blockingServerStreamingCall(
      ClientCall<ReqT, RespT> call, ReqT req) {
    BlockingResponseStream<RespT> result = new BlockingResponseStream<>(call);
    asyncUnaryRequestCall(call, req, result.listener());
    return result;
  }

  /**
   * Executes a server-streaming call returning a blocking {@link Iterator} over the
   * response stream.
   *
   * <p>The returned iterator may throw {@link StatusRuntimeException} on error.
   *
   * <p>Warning:  the iterator can result in leaks if not completely consumed.
   *
   * @return an iterator over the response stream.
   */
  public static <ReqT, RespT> Iterator<RespT> blockingServerStreamingCall(
      Channel channel, MethodDescriptor<ReqT, RespT> method, CallOptions callOptions, ReqT req) {
    ClientCall<ReqT, RespT> call = channel.newCall(method,
        callOptions.withOption(ClientCalls.STUB_TYPE_OPTION, StubType.BLOCKING));

    BlockingResponseStream<RespT> result = new BlockingResponseStream<>(call);
    asyncUnaryRequestCall(call, req, result.listener());
    return result;
  }

  /**
   * Initiates a client streaming call over the specified channel.  It returns an
   * object which can be used in a blocking manner to send values to the server and retrieve a
   * response.
   *
   * <p>Call {@link BlockingClientCall#write} for each value to send to the server. After the last
   * value has been written, call {@link BlockingClientCall#halfClose} to indicate that writing is
   * complete and then {@link BlockingClientCall#read} to get the response.
   *
   * <p>The methods {@link BlockingClientCall#hasNext()} and {@link
   * BlockingClientCall#cancel(String, Throwable)} can be used for more extensive control.
   *
   * @return A {@link BlockingClientCall} that has had the request sent and halfClose called
   * @throws InterruptedException if it receives an interrupt while sending the request
   * @throws StatusException if the write to the server failed
   */
  @ExperimentalApi("https://github.com/grpc/grpc-java/issues/10918")
  public static <ReqT, RespT> BlockingClientCall<?, RespT> blockingV2ServerStreamingCall(
      Channel channel, MethodDescriptor<ReqT, RespT> method, CallOptions callOptions, ReqT req)
      throws InterruptedException, StatusException {
    BlockingClientCall<ReqT, RespT> call =
        blockingBidiStreamingCall(channel, method, callOptions);

    try {
      call.write(req);
      call.halfClose();
      return call;
    } catch (InterruptedException e) {
      call.cancel("Interrupted while writing request", e);
      throw e;
    }
  }

  /**
   * Initiates a server streaming call and sends the specified request to the server.  It returns an
   * object which can be used in a blocking manner to retrieve values from the server.  After the
   * last value has been read, the next read call will return null.
   *
   * <p>Call {@link BlockingClientCall#read()} for
   * retrieving values.  A {@code null} will be returned after the server has closed the stream.
   *
   * <p>The methods {@link BlockingClientCall#hasNext()} and {@link
   * BlockingClientCall#cancel(String, Throwable)} can be used for more extensive control.
   *
   * <p><br> Example usage:
   * <pre> {@code  while ((response = call.read()) != null) { ... } } </pre>
   * or
   * <pre> {@code
   *   while (call.hasNext()) {
   *     response = call.read();
   *     ...
   *   }
   * } </pre>
   *
   * <p>Note that this paradigm is different from the original
   * {@link #blockingServerStreamingCall(Channel, MethodDescriptor, CallOptions, Object)}
   * which returns an iterator, which would leave the stream open if not completely consumed.
   *
   * @return A {@link BlockingClientCall} which can be used by the client to write and receive
   *     messages over the grpc channel.
   */
  @ExperimentalApi("https://github.com/grpc/grpc-java/issues/10918")
  public static <ReqT, RespT> BlockingClientCall<ReqT, RespT> blockingClientStreamingCall(
      Channel channel, MethodDescriptor<ReqT, RespT> method, CallOptions callOptions) {
    return blockingBidiStreamingCall(channel, method, callOptions);
  }

  /**
   * Initiate a bidirectional-streaming {@link ClientCall} and returning a stream object
   * ({@link BlockingClientCall}) which can be used by the client to send and receive messages over
   * the grpc channel.
   *
   * @return an object representing the call which can be used to read, write and terminate it.
   */
  @ExperimentalApi("https://github.com/grpc/grpc-java/issues/10918")
  public static <ReqT, RespT> BlockingClientCall<ReqT, RespT> blockingBidiStreamingCall(
      Channel channel, MethodDescriptor<ReqT, RespT> method, CallOptions callOptions) {
    ThreadSafeThreadlessExecutor executor = new ThreadSafeThreadlessExecutor();
    ClientCall<ReqT, RespT> call = channel.newCall(method, callOptions.withExecutor(executor));

    BlockingClientCall<ReqT, RespT> blockingClientCall = new BlockingClientCall<>(call, executor);

    // Get the call started
    call.start(blockingClientCall.getListener(), new Metadata());
    call.request(1);

    return blockingClientCall;
  }

  /**
   * Executes a unary call and returns a {@link ListenableFuture} to the response.  The
   * {@code call} should not be already started.  After calling this method, {@code call} should no
   * longer be used.
   *
   * @return a future for the single response message.
   */
  public static <ReqT, RespT> ListenableFuture<RespT> futureUnaryCall(
      ClientCall<ReqT, RespT> call, ReqT req) {
    GrpcFuture<RespT> responseFuture = new GrpcFuture<>(call);
    asyncUnaryRequestCall(call, req, new UnaryStreamToFuture<>(responseFuture));
    return responseFuture;
  }

  /**
   * Returns the result of calling {@link Future#get()} interruptibly on a task known not to throw a
   * checked exception.
   *
   * <p>If interrupted, the interrupt is restored before throwing an exception..
   *
   * @throws java.util.concurrent.CancellationException
   *     if {@code get} throws a {@code CancellationException}.
   * @throws io.grpc.StatusRuntimeException if {@code get} throws an {@link ExecutionException}
   *     or an {@link InterruptedException}.
   */
  private static <V> V getUnchecked(Future<V> future) {
    try {
      return future.get();
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      throw Status.CANCELLED
          .withDescription("Thread interrupted")
          .withCause(e)
          .asRuntimeException();
    } catch (ExecutionException e) {
      throw toStatusRuntimeException(e.getCause());
    }
  }

  /**
   * Wraps the given {@link Throwable} in a {@link StatusRuntimeException}. If it contains an
   * embedded {@link StatusException} or {@link StatusRuntimeException}, the returned exception will
   * contain the embedded trailers and status, with the given exception as the cause. Otherwise, an
   * exception will be generated from an {@link Status#UNKNOWN} status.
   */
  private static StatusRuntimeException toStatusRuntimeException(Throwable t) {
    Throwable cause = checkNotNull(t, "t");
    while (cause != null) {
      // If we have an embedded status, use it and replace the cause
      if (cause instanceof StatusException) {
        StatusException se = (StatusException) cause;
        return new StatusRuntimeException(se.getStatus(), se.getTrailers());
      } else if (cause instanceof StatusRuntimeException) {
        StatusRuntimeException se = (StatusRuntimeException) cause;
        return new StatusRuntimeException(se.getStatus(), se.getTrailers());
      }
      cause = cause.getCause();
    }
    return Status.UNKNOWN.withDescription("unexpected exception").withCause(t)
        .asRuntimeException();
  }

  /**
   * Cancels a call, and throws the exception.
   *
   * @param t must be a RuntimeException or Error
   */
  private static RuntimeException cancelThrow(ClientCall<?, ?> call, Throwable t) {
    try {
      call.cancel(null, t);
    } catch (RuntimeException | Error e) {
      logger.log(Level.SEVERE, "RuntimeException encountered while closing call", e);
    }
    if (t instanceof RuntimeException) {
      throw (RuntimeException) t;
    } else if (t instanceof Error) {
      throw (Error) t;
    }
    // should be impossible
    throw new AssertionError(t);
  }

  private static <ReqT, RespT> void asyncUnaryRequestCall(
      ClientCall<ReqT, RespT> call, ReqT req, StreamObserver<RespT> responseObserver,
      boolean streamingResponse) {
    asyncUnaryRequestCall(
        call,
        req,
        new StreamObserverToCallListenerAdapter<>(
            responseObserver,
            new CallToStreamObserverAdapter<>(call, streamingResponse)));
  }

  private static <ReqT, RespT> void asyncUnaryRequestCall(
      ClientCall<ReqT, RespT> call,
      ReqT req,
      StartableListener<RespT> responseListener) {
    startCall(call, responseListener);
    try {
      call.sendMessage(req);
      call.halfClose();
    } catch (RuntimeException | Error e) {
      throw cancelThrow(call, e);
    }
  }

  private static <ReqT, RespT> StreamObserver<ReqT> asyncStreamingRequestCall(
      ClientCall<ReqT, RespT> call,
      StreamObserver<RespT> responseObserver,
      boolean streamingResponse) {
    CallToStreamObserverAdapter<ReqT> adapter = new CallToStreamObserverAdapter<>(
        call, streamingResponse);
    startCall(
        call,
        new StreamObserverToCallListenerAdapter<>(responseObserver, adapter));
    return adapter;
  }

  private static <ReqT, RespT> void startCall(
      ClientCall<ReqT, RespT> call,
      StartableListener<RespT> responseListener) {
    call.start(responseListener, new Metadata());
    responseListener.onStart();
  }

  private abstract static class StartableListener<T> extends ClientCall.Listener<T> {
    abstract void onStart();
  }

  private static class CallToStreamObserverAdapter<ReqT>
      extends ClientCallStreamObserver<ReqT> {
    private boolean frozen;
    private final ClientCall<ReqT, ?> call;
    private final boolean streamingResponse;
    private Runnable onReadyHandler;
    private int initialRequest = 1;
    private boolean autoRequestEnabled = true;
    private boolean aborted = false;
    private boolean completed = false;

    // Non private to avoid synthetic class
    CallToStreamObserverAdapter(ClientCall<ReqT, ?> call, boolean streamingResponse) {
      this.call = call;
      this.streamingResponse = streamingResponse;
    }

    private void freeze() {
      this.frozen = true;
    }

    @Override
    public void onNext(ReqT value) {
      checkState(!aborted, "Stream was terminated by error, no further calls are allowed");
      checkState(!completed, "Stream is already completed, no further calls are allowed");
      call.sendMessage(value);
    }

    @Override
    public void onError(Throwable t) {
      call.cancel("Cancelled by client with StreamObserver.onError()", t);
      aborted = true;
    }

    @Override
    public void onCompleted() {
      call.halfClose();
      completed = true;
    }

    @Override
    public boolean isReady() {
      return call.isReady();
    }

    @Override
    public void setOnReadyHandler(Runnable onReadyHandler) {
      if (frozen) {
        throw new IllegalStateException(
            "Cannot alter onReadyHandler after call started. Use ClientResponseObserver");
      }
      this.onReadyHandler = onReadyHandler;
    }

    @Override
    public void disableAutoInboundFlowControl() {
      disableAutoRequestWithInitial(1);
    }

    @Override
    public void disableAutoRequestWithInitial(int request) {
      if (frozen) {
        throw new IllegalStateException(
            "Cannot disable auto flow control after call started. Use ClientResponseObserver");
      }
      Preconditions.checkArgument(request >= 0, "Initial requests must be non-negative");
      initialRequest = request;
      autoRequestEnabled = false;
    }

    @Override
    public void request(int count) {
      if (!streamingResponse && count == 1) {
        // Initially ask for two responses from flow-control so that if a misbehaving server
        // sends more than one response, we can catch it and fail it in the listener.
        call.request(2);
      } else {
        call.request(count);
      }
    }

    @Override
    public void setMessageCompression(boolean enable) {
      call.setMessageCompression(enable);
    }

    @Override
    public void cancel(@Nullable String message, @Nullable Throwable cause) {
      call.cancel(message, cause);
    }
  }

  private static final class StreamObserverToCallListenerAdapter<ReqT, RespT>
      extends StartableListener<RespT> {
    private final StreamObserver<RespT> observer;
    private final CallToStreamObserverAdapter<ReqT> adapter;
    private boolean firstResponseReceived;

    // Non private to avoid synthetic class
    StreamObserverToCallListenerAdapter(
        StreamObserver<RespT> observer,
        CallToStreamObserverAdapter<ReqT> adapter) {
      this.observer = observer;
      this.adapter = adapter;
      if (observer instanceof ClientResponseObserver) {
        @SuppressWarnings("unchecked")
        ClientResponseObserver<ReqT, RespT> clientResponseObserver =
            (ClientResponseObserver<ReqT, RespT>) observer;
        clientResponseObserver.beforeStart(adapter);
      }
      adapter.freeze();
    }

    @Override
    public void onHeaders(Metadata headers) {
    }

    @Override
    public void onMessage(RespT message) {
      if (firstResponseReceived && !adapter.streamingResponse) {
        throw Status.INTERNAL
            .withDescription("More than one responses received for unary or client-streaming call")
            .asRuntimeException();
      }
      firstResponseReceived = true;
      observer.onNext(message);

      if (adapter.streamingResponse && adapter.autoRequestEnabled) {
        // Request delivery of the next inbound message.
        adapter.request(1);
      }
    }

    @Override
    public void onClose(Status status, Metadata trailers) {
      if (status.isOk()) {
        observer.onCompleted();
      } else {
        observer.onError(status.asRuntimeException(trailers));
      }
    }

    @Override
    public void onReady() {
      if (adapter.onReadyHandler != null) {
        adapter.onReadyHandler.run();
      }
    }

    @Override
    void onStart() {
      if (adapter.initialRequest > 0) {
        adapter.request(adapter.initialRequest);
      }
    }
  }

  /**
   * Completes a {@link GrpcFuture} using {@link StreamObserver} events.
   */
  private static final class UnaryStreamToFuture<RespT> extends StartableListener<RespT> {
    private final GrpcFuture<RespT> responseFuture;
    private RespT value;
    private boolean isValueReceived = false;

    // Non private to avoid synthetic class
    UnaryStreamToFuture(GrpcFuture<RespT> responseFuture) {
      this.responseFuture = responseFuture;
    }

    @Override
    public void onHeaders(Metadata headers) {
    }

    @Override
    public void onMessage(RespT value) {
      if (this.isValueReceived) {
        throw Status.INTERNAL.withDescription("More than one value received for unary call")
            .asRuntimeException();
      }
      this.value = value;
      this.isValueReceived = true;
    }

    @Override
    public void onClose(Status status, Metadata trailers) {
      if (status.isOk()) {
        if (!isValueReceived) {
          // No value received so mark the future as an error
          responseFuture.setException(
              Status.INTERNAL.withDescription("No value received for unary call")
                  .asRuntimeException(trailers));
        }
        responseFuture.set(value);
      } else {
        responseFuture.setException(status.asRuntimeException(trailers));
      }
    }

    @Override
    void onStart() {
      responseFuture.call.request(2);
    }
  }

  private static final class GrpcFuture<RespT> extends AbstractFuture<RespT> {
    private final ClientCall<?, RespT> call;

    // Non private to avoid synthetic class
    GrpcFuture(ClientCall<?, RespT> call) {
      this.call = call;
    }

    @Override
    protected void interruptTask() {
      call.cancel("GrpcFuture was cancelled", null);
    }

    @Override
    protected boolean set(@Nullable RespT resp) {
      return super.set(resp);
    }

    @Override
    protected boolean setException(Throwable throwable) {
      return super.setException(throwable);
    }

    @SuppressWarnings("MissingOverride") // Add @Override once Java 6 support is dropped
    protected String pendingToString() {
      return MoreObjects.toStringHelper(this).add("clientCall", call).toString();
    }
  }

  /**
   * Convert events on a {@link io.grpc.ClientCall.Listener} into a blocking {@link Iterator}.
   *
   * <p>The class is not thread-safe, but it does permit {@link ClientCall.Listener} calls in a
   * separate thread from {@link Iterator} calls.
   */
  // TODO(ejona86): determine how to allow ClientCall.cancel() in case of application error.
  private static final class BlockingResponseStream<T> implements Iterator<T> {
    // Due to flow control, only needs to hold up to 3 items: 2 for value, 1 for close.
    // (2 for value, not 1, because of early request() in next())
    private final BlockingQueue<Object> buffer = new ArrayBlockingQueue<>(3);
    private final StartableListener<T> listener = new QueuingListener();
    private final ClientCall<?, T> call;
    // Only accessed when iterating.
    private Object last;

    // Non private to avoid synthetic class
    BlockingResponseStream(ClientCall<?, T> call) {
      this.call = call;
    }

    StartableListener<T> listener() {
      return listener;
    }

    private Object waitForNext() {
      boolean interrupt = false;
      try {
        while (true) {
          try {
            return buffer.take();
          } catch (InterruptedException ie) {
            interrupt = true;
            call.cancel("Thread interrupted", ie);
            // Now wait for onClose() to be called, to guarantee BlockingQueue doesn't fill
          }
        }
      } finally {
        if (interrupt) {
          Thread.currentThread().interrupt();
        }
      }
    }

    @Override
    public boolean hasNext() {
      while (last == null) {
        // Will block here indefinitely waiting for content. RPC timeouts defend against permanent
        // hangs here as the call will become closed.
        last = waitForNext();
      }
      if (last instanceof StatusRuntimeException) {
        // Rethrow the exception with a new stacktrace.
        StatusRuntimeException e = (StatusRuntimeException) last;
        throw e.getStatus().asRuntimeException(e.getTrailers());
      }
      return last != this;
    }

    @Override
    public T next() {
      // Eagerly call request(1) so it can be processing the next message while we wait for the
      // current one, which reduces latency for the next message. With MigratingThreadDeframer and
      // if the data has already been received, every other message can be delivered instantly. This
      // can be run after hasNext(), but just would be slower.
      if (!(last instanceof StatusRuntimeException) && last != this) {
        call.request(1);
      }
      if (!hasNext()) {
        throw new NoSuchElementException();
      }
      @SuppressWarnings("unchecked")
      T tmp = (T) last;
      last = null;
      return tmp;
    }

    @Override
    public void remove() {
      throw new UnsupportedOperationException();
    }

    private final class QueuingListener extends StartableListener<T> {
      // Non private to avoid synthetic class
      QueuingListener() {}

      private boolean done = false;

      @Override
      public void onHeaders(Metadata headers) {
      }

      @Override
      public void onMessage(T value) {
        Preconditions.checkState(!done, "ClientCall already closed");
        buffer.add(value);
      }

      @Override
      public void onClose(Status status, Metadata trailers) {
        Preconditions.checkState(!done, "ClientCall already closed");
        if (status.isOk()) {
          buffer.add(BlockingResponseStream.this);
        } else {
          buffer.add(status.asRuntimeException(trailers));
        }
        done = true;
      }

      @Override
      void onStart() {
        call.request(1);
      }
    }
  }

  @SuppressWarnings("serial")
  static final class ThreadlessExecutor extends ConcurrentLinkedQueue<Runnable>
      implements Executor {
    private static final Logger log = Logger.getLogger(ThreadlessExecutor.class.getName());

    private static final Object SHUTDOWN = new Object(); // sentinel

    // Set to the calling thread while it's parked, SHUTDOWN on RPC completion
    private volatile Object waiter;

    // Non private to avoid synthetic class
    ThreadlessExecutor() {}

    /**
     * Waits until there is a Runnable, then executes it and all queued Runnables after it.
     * Must only be called by one thread at a time.
     */
    public void waitAndDrain() throws InterruptedException {
      Runnable runnable = poll();
      if (runnable == null) {
        waiter = Thread.currentThread();
        try {
          while ((runnable = poll()) == null) {
            LockSupport.park(this);
          }
        } finally {
          waiter = null;
        }
      }
      do {
        runQuietly(runnable);
      } while ((runnable = poll()) != null);
    }

    /**
     * Called after final call to {@link #waitAndDrain()}, from same thread.
     */
    public void shutdown() {
      waiter = SHUTDOWN;
      Runnable runnable;
      while ((runnable = poll()) != null) {
        runQuietly(runnable);
      }
    }

    private static void runQuietly(Runnable runnable) {
      try {
        runnable.run();
      } catch (Throwable t) {
        log.log(Level.WARNING, "Runnable threw exception", t);
      }
    }

    @Override
    public void execute(Runnable runnable) {
      add(runnable);
      Object waiter = this.waiter;
      if (waiter != SHUTDOWN) {
        LockSupport.unpark((Thread) waiter); // no-op if null
      } else if (remove(runnable) && rejectRunnableOnExecutor) {
        throw new RejectedExecutionException();
      }
    }
  }

  @SuppressWarnings("serial")
  static final class ThreadSafeThreadlessExecutor extends ConcurrentLinkedQueue<Runnable>
      implements Executor {
    private static final Logger log =
        Logger.getLogger(ThreadSafeThreadlessExecutor.class.getName());

    private Lock waiterLock = new ReentrantLock();
    private final Condition waiterCondition = waiterLock.newCondition();

    // Non private to avoid synthetic class
    ThreadSafeThreadlessExecutor() {}

    /**
     * Waits until there is a Runnable, then executes it and all queued Runnables after it.
     */
    public <T> void waitAndDrain(Predicate<T> predicate, T testTarget) throws InterruptedException {
      try {
        waitAndDrainWithTimeout(true, 0, predicate, testTarget);
      } catch (TimeoutException e) {
        throw new AssertionError(e); // Should never happen
      }
    }

    /**
     * Waits for up to specified nanoseconds until there is a Runnable, then executes it and all
     * queued Runnables after it.
     *
     * <p>his should always be called in a loop that checks whether the reason we are waiting has
     * been satisfied.</p>T
     *
     * @param waitForever ignore the rest of the arguments and wait until there is a task to run
     * @param end System.nanoTime() to stop waiting if haven't been woken up yet
     * @param predicate non-null condition to test for skipping wake or waking up threads
     * @param testTarget object to pass to predicate
     */
    public <T> void waitAndDrainWithTimeout(boolean waitForever, long end,
                                            @Nonnull Predicate<T> predicate, T testTarget)
        throws InterruptedException, TimeoutException {
      throwIfInterrupted();
      Runnable runnable;

      while (!predicate.test(testTarget)) {
        waiterLock.lock();
        try {
          while ((runnable = poll()) == null) {
            if (predicate.test(testTarget)) {
              return; // The condition for which we were waiting is now satisfied
            }

            if (waitForever) {
              waiterCondition.await();
            } else {
              long waitNanos = end - System.nanoTime();
              if (waitNanos <= 0) {
                throw new TimeoutException(); // Deadline is expired
              }
              waiterCondition.awaitNanos(waitNanos);
            }
          }
        } finally {
          waiterLock.unlock();
        }

        do {
          runQuietly(runnable);
        } while ((runnable = poll()) != null);
        // Wake everything up now that we've done something and they can check in their outer loop
        // if they can continue or need to wait again.
        signallAll();
      }
    }

    /**
     * Executes all queued Runnables and if there were any wakes up any waiting threads.
     */
    public void drain() throws InterruptedException {
      throwIfInterrupted();
      Runnable runnable;
      boolean didWork = false;

      while ((runnable = poll()) != null) {
        runQuietly(runnable);
        didWork = true;
      }

      if (didWork) {
        signallAll();
      }
    }

    private void signallAll() {
      waiterLock.lock();
      try {
        waiterCondition.signalAll();
      } finally {
        waiterLock.unlock();
      }
    }

    private static void runQuietly(Runnable runnable) {
      try {
        runnable.run();
      } catch (Throwable t) {
        log.log(Level.WARNING, "Runnable threw exception", t);
      }
    }

    private static void throwIfInterrupted() throws InterruptedException {
      if (Thread.interrupted()) {
        throw new InterruptedException();
      }
    }

    @Override
    public void execute(Runnable runnable) {
      waiterLock.lock();
      try {
        add(runnable);
        waiterCondition.signalAll(); // If anything is waiting let it wake up and process this task
      } finally {
        waiterLock.unlock();
      }
    }
  }

  enum StubType {
    BLOCKING, FUTURE, ASYNC
  }

  /**
   * Internal {@link CallOptions.Key} to indicate stub types.
   */
  static final CallOptions.Key<StubType> STUB_TYPE_OPTION =
      CallOptions.Key.create("internal-stub-type");
}
