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

package io.grpc.internal;

import static com.google.common.base.Preconditions.checkArgument;
import static com.google.common.base.Preconditions.checkNotNull;
import static com.google.common.base.Preconditions.checkState;
import static com.google.common.util.concurrent.MoreExecutors.directExecutor;
import static io.grpc.ClientStreamTracer.NAME_RESOLUTION_DELAYED;
import static io.grpc.Contexts.statusFromCancelled;
import static io.grpc.Status.DEADLINE_EXCEEDED;
import static io.grpc.internal.GrpcUtil.CONTENT_ACCEPT_ENCODING_KEY;
import static io.grpc.internal.GrpcUtil.CONTENT_ENCODING_KEY;
import static io.grpc.internal.GrpcUtil.CONTENT_LENGTH_KEY;
import static io.grpc.internal.GrpcUtil.MESSAGE_ACCEPT_ENCODING_KEY;
import static io.grpc.internal.GrpcUtil.MESSAGE_ENCODING_KEY;

import com.google.common.annotations.VisibleForTesting;
import com.google.common.base.MoreObjects;
import io.grpc.Attributes;
import io.grpc.CallOptions;
import io.grpc.ClientCall;
import io.grpc.ClientStreamTracer;
import io.grpc.Codec;
import io.grpc.Compressor;
import io.grpc.CompressorRegistry;
import io.grpc.Context;
import io.grpc.Context.CancellationListener;
import io.grpc.Deadline;
import io.grpc.DecompressorRegistry;
import io.grpc.InternalConfigSelector;
import io.grpc.InternalDecompressorRegistry;
import io.grpc.Metadata;
import io.grpc.MethodDescriptor;
import io.grpc.MethodDescriptor.MethodType;
import io.grpc.Status;
import io.grpc.internal.ManagedChannelServiceConfig.MethodInfo;
import io.perfmark.Link;
import io.perfmark.PerfMark;
import io.perfmark.Tag;
import io.perfmark.TaskCloseable;
import java.io.InputStream;
import java.nio.charset.Charset;
import java.util.Locale;
import java.util.concurrent.CancellationException;
import java.util.concurrent.Executor;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.logging.Level;
import java.util.logging.Logger;
import javax.annotation.Nullable;

/**
 * Implementation of {@link ClientCall}.
 */
final class ClientCallImpl<ReqT, RespT> extends ClientCall<ReqT, RespT> {

  private static final Logger log = Logger.getLogger(ClientCallImpl.class.getName());
  private static final byte[] FULL_STREAM_DECOMPRESSION_ENCODINGS
      = "gzip".getBytes(Charset.forName("US-ASCII"));
  private static final double NANO_TO_SECS = 1.0 * TimeUnit.SECONDS.toNanos(1);

  private final MethodDescriptor<ReqT, RespT> method;
  private final Tag tag;
  private final Executor callExecutor;
  private final boolean callExecutorIsDirect;
  private final CallTracer channelCallsTracer;
  private final Context context;
  private CancellationHandler cancellationHandler;
  private final boolean unaryRequest;
  private CallOptions callOptions;
  private ClientStream stream;
  private boolean cancelCalled;
  private boolean halfCloseCalled;
  private final ClientStreamProvider clientStreamProvider;
  private final ScheduledExecutorService deadlineCancellationExecutor;
  private boolean fullStreamDecompression;
  private DecompressorRegistry decompressorRegistry = DecompressorRegistry.getDefaultInstance();
  private CompressorRegistry compressorRegistry = CompressorRegistry.getDefaultInstance();

  ClientCallImpl(
      MethodDescriptor<ReqT, RespT> method, Executor executor, CallOptions callOptions,
      ClientStreamProvider clientStreamProvider,
      ScheduledExecutorService deadlineCancellationExecutor,
      CallTracer channelCallsTracer,
      // TODO(zdapeng): remove this arg
      @Nullable InternalConfigSelector configSelector) {
    this.method = method;
    // TODO(carl-mastrangelo): consider moving this construction to ManagedChannelImpl.
    this.tag = PerfMark.createTag(method.getFullMethodName(), System.identityHashCode(this));
    // If we know that the executor is a direct executor, we don't need to wrap it with a
    // SerializingExecutor. This is purely for performance reasons.
    // See https://github.com/grpc/grpc-java/issues/368
    if (executor == directExecutor()) {
      this.callExecutor = new SerializeReentrantCallsDirectExecutor();
      callExecutorIsDirect = true;
    } else {
      this.callExecutor = new SerializingExecutor(executor);
      callExecutorIsDirect = false;
    }
    this.channelCallsTracer = channelCallsTracer;
    // Propagate the context from the thread which initiated the call to all callbacks.
    this.context = Context.current();
    this.unaryRequest = method.getType() == MethodType.UNARY
        || method.getType() == MethodType.SERVER_STREAMING;
    this.callOptions = callOptions;
    this.clientStreamProvider = clientStreamProvider;
    this.deadlineCancellationExecutor = deadlineCancellationExecutor;
    PerfMark.event("ClientCall.<init>", tag);
  }

  /**
   * Provider of {@link ClientStream}s.
   */
  interface ClientStreamProvider {
    ClientStream newStream(
        MethodDescriptor<?, ?> method,
        CallOptions callOptions,
        Metadata headers,
        Context context);
  }

  ClientCallImpl<ReqT, RespT> setFullStreamDecompression(boolean fullStreamDecompression) {
    this.fullStreamDecompression = fullStreamDecompression;
    return this;
  }

  ClientCallImpl<ReqT, RespT> setDecompressorRegistry(DecompressorRegistry decompressorRegistry) {
    this.decompressorRegistry = decompressorRegistry;
    return this;
  }

  ClientCallImpl<ReqT, RespT> setCompressorRegistry(CompressorRegistry compressorRegistry) {
    this.compressorRegistry = compressorRegistry;
    return this;
  }

  @VisibleForTesting
  static void prepareHeaders(
      Metadata headers,
      DecompressorRegistry decompressorRegistry,
      Compressor compressor,
      boolean fullStreamDecompression) {
    headers.discardAll(CONTENT_LENGTH_KEY);
    headers.discardAll(MESSAGE_ENCODING_KEY);
    if (compressor != Codec.Identity.NONE) {
      headers.put(MESSAGE_ENCODING_KEY, compressor.getMessageEncoding());
    }

    headers.discardAll(MESSAGE_ACCEPT_ENCODING_KEY);
    byte[] advertisedEncodings =
        InternalDecompressorRegistry.getRawAdvertisedMessageEncodings(decompressorRegistry);
    if (advertisedEncodings.length != 0) {
      headers.put(MESSAGE_ACCEPT_ENCODING_KEY, advertisedEncodings);
    }

    headers.discardAll(CONTENT_ENCODING_KEY);
    headers.discardAll(CONTENT_ACCEPT_ENCODING_KEY);
    if (fullStreamDecompression) {
      headers.put(CONTENT_ACCEPT_ENCODING_KEY, FULL_STREAM_DECOMPRESSION_ENCODINGS);
    }
  }

  @Override
  public void start(Listener<RespT> observer, Metadata headers) {
    try (TaskCloseable ignore = PerfMark.traceTask("ClientCall.start")) {
      PerfMark.attachTag(tag);
      startInternal(observer, headers);
    }
  }

  private void startInternal(Listener<RespT> observer, Metadata headers) {
    checkState(stream == null, "Already started");
    checkState(!cancelCalled, "call was cancelled");
    checkNotNull(observer, "observer");
    checkNotNull(headers, "headers");

    if (context.isCancelled()) {
      // Context is already cancelled so no need to create a real stream, just notify the observer
      // of cancellation via callback on the executor
      stream = NoopClientStream.INSTANCE;
      final Listener<RespT> finalObserver = observer;
      class ClosedByContext extends ContextRunnable {
        ClosedByContext() {
          super(context);
        }

        @Override
        public void runInContext() {
          closeObserver(finalObserver, statusFromCancelled(context), new Metadata());
        }
      }

      callExecutor.execute(new ClosedByContext());
      return;
    }
    applyMethodConfig();
    final String compressorName = callOptions.getCompressor();
    Compressor compressor;
    if (compressorName != null) {
      compressor = compressorRegistry.lookupCompressor(compressorName);
      if (compressor == null) {
        stream = NoopClientStream.INSTANCE;
        final Listener<RespT> finalObserver = observer;
        class ClosedByNotFoundCompressor extends ContextRunnable {
          ClosedByNotFoundCompressor() {
            super(context);
          }

          @Override
          public void runInContext() {
            closeObserver(
                finalObserver,
                Status.INTERNAL.withDescription(
                    String.format("Unable to find compressor by name %s", compressorName)),
                new Metadata());
          }
        }

        callExecutor.execute(new ClosedByNotFoundCompressor());
        return;
      }
    } else {
      compressor = Codec.Identity.NONE;
    }
    prepareHeaders(headers, decompressorRegistry, compressor, fullStreamDecompression);

    Deadline effectiveDeadline = effectiveDeadline();
    boolean contextIsDeadlineSource = effectiveDeadline != null
        && effectiveDeadline.equals(context.getDeadline());
    cancellationHandler = new CancellationHandler(effectiveDeadline, contextIsDeadlineSource);
    boolean deadlineExceeded = effectiveDeadline != null && cancellationHandler.remainingNanos <= 0;
    if (!deadlineExceeded) {
      stream = clientStreamProvider.newStream(method, callOptions, headers, context);
    } else {
      ClientStreamTracer[] tracers =
          GrpcUtil.getClientStreamTracers(callOptions, headers, 0, false);
      String deadlineName = contextIsDeadlineSource ? "Context" : "CallOptions";
      Long nameResolutionDelay = callOptions.getOption(NAME_RESOLUTION_DELAYED);
      String description = String.format(
          "ClientCall started after %s deadline was exceeded %.9f seconds ago. "
              + "Name resolution delay %.9f seconds.", deadlineName,
          cancellationHandler.remainingNanos / NANO_TO_SECS,
          nameResolutionDelay == null ? 0 : nameResolutionDelay / NANO_TO_SECS);
      stream = new FailingClientStream(DEADLINE_EXCEEDED.withDescription(description), tracers);
    }

    if (callExecutorIsDirect) {
      stream.optimizeForDirectExecutor();
    }
    if (callOptions.getAuthority() != null) {
      stream.setAuthority(callOptions.getAuthority());
    }
    if (callOptions.getMaxInboundMessageSize() != null) {
      stream.setMaxInboundMessageSize(callOptions.getMaxInboundMessageSize());
    }
    if (callOptions.getMaxOutboundMessageSize() != null) {
      stream.setMaxOutboundMessageSize(callOptions.getMaxOutboundMessageSize());
    }
    if (effectiveDeadline != null) {
      stream.setDeadline(effectiveDeadline);
    }
    stream.setCompressor(compressor);
    if (fullStreamDecompression) {
      stream.setFullStreamDecompression(fullStreamDecompression);
    }
    stream.setDecompressorRegistry(decompressorRegistry);
    channelCallsTracer.reportCallStarted();
    stream.start(new ClientStreamListenerImpl(observer));

    // Delay any sources of cancellation after start(), because most of the transports are broken if
    // they receive cancel before start. Issue #1343 has more details

    // Propagate later Context cancellation to the remote side.
    cancellationHandler.setUp();
  }

  private void applyMethodConfig() {
    MethodInfo info = callOptions.getOption(MethodInfo.KEY);
    if (info == null) {
      return;
    }
    if (info.timeoutNanos != null) {
      Deadline newDeadline = Deadline.after(info.timeoutNanos, TimeUnit.NANOSECONDS);
      Deadline existingDeadline = callOptions.getDeadline();
      // If the new deadline is sooner than the existing deadline, swap them.
      if (existingDeadline == null || newDeadline.compareTo(existingDeadline) < 0) {
        callOptions = callOptions.withDeadline(newDeadline);
      }
    }
    if (info.waitForReady != null) {
      callOptions =
          info.waitForReady ? callOptions.withWaitForReady() : callOptions.withoutWaitForReady();
    }
    if (info.maxInboundMessageSize != null) {
      Integer existingLimit = callOptions.getMaxInboundMessageSize();
      if (existingLimit != null) {
        callOptions =
            callOptions.withMaxInboundMessageSize(
                Math.min(existingLimit, info.maxInboundMessageSize));
      } else {
        callOptions = callOptions.withMaxInboundMessageSize(info.maxInboundMessageSize);
      }
    }
    if (info.maxOutboundMessageSize != null) {
      Integer existingLimit = callOptions.getMaxOutboundMessageSize();
      if (existingLimit != null) {
        callOptions =
            callOptions.withMaxOutboundMessageSize(
                Math.min(existingLimit, info.maxOutboundMessageSize));
      } else {
        callOptions = callOptions.withMaxOutboundMessageSize(info.maxOutboundMessageSize);
      }
    }
  }

  private final class CancellationHandler implements Runnable, CancellationListener {
    private final boolean contextIsDeadlineSource;
    private final boolean hasDeadline;
    private final long remainingNanos;
    private volatile ScheduledFuture<?> deadlineCancellationFuture;
    private volatile boolean tearDownCalled;

    CancellationHandler(Deadline deadline, boolean contextIsDeadlineSource) {
      this.contextIsDeadlineSource = contextIsDeadlineSource;
      if (deadline == null) {
        hasDeadline = false;
        remainingNanos = 0;
      } else {
        hasDeadline = true;
        remainingNanos = deadline.timeRemaining(TimeUnit.NANOSECONDS);
      }
    }

    void setUp() {
      if (tearDownCalled) {
        return;
      }
      if (hasDeadline
          // If the context has the effective deadline, we don't need to schedule an extra task.
          && !contextIsDeadlineSource
          // If the channel has been terminated, we don't need to schedule an extra task.
          && deadlineCancellationExecutor != null) {
        deadlineCancellationFuture = deadlineCancellationExecutor.schedule(
            new LogExceptionRunnable(this), remainingNanos, TimeUnit.NANOSECONDS);
      }
      context.addListener(this, directExecutor());
      if (tearDownCalled) {
        // Race detected! Re-run to make sure the future is cancelled and context listener removed
        tearDown();
      }
    }

    // May be called multiple times, and race with setUp()
    void tearDown() {
      tearDownCalled = true;
      ScheduledFuture<?> deadlineCancellationFuture = this.deadlineCancellationFuture;
      if (deadlineCancellationFuture != null) {
        deadlineCancellationFuture.cancel(false);
      }
      context.removeListener(this);
    }

    @Override
    public void cancelled(Context context) {
      if (hasDeadline && contextIsDeadlineSource
          && context.cancellationCause() instanceof TimeoutException) {
        stream.cancel(formatDeadlineExceededStatus());
        return;
      }
      stream.cancel(statusFromCancelled(context));
    }

    @Override
    public void run() {
      stream.cancel(formatDeadlineExceededStatus());
    }

    Status formatDeadlineExceededStatus() {
      // DelayedStream.cancel() is safe to call from a thread that is different from where the
      // stream is created.
      long seconds = Math.abs(remainingNanos) / TimeUnit.SECONDS.toNanos(1);
      long nanos = Math.abs(remainingNanos) % TimeUnit.SECONDS.toNanos(1);

      StringBuilder buf = new StringBuilder();
      buf.append(contextIsDeadlineSource ? "Context" : "CallOptions");
      buf.append(" deadline exceeded after ");
      if (remainingNanos < 0) {
        buf.append('-');
      }
      buf.append(seconds);
      buf.append(String.format(Locale.US, ".%09d", nanos));
      buf.append("s. ");
      Long nsDelay = callOptions.getOption(NAME_RESOLUTION_DELAYED);
      buf.append(String.format(Locale.US, "Name resolution delay %.9f seconds.",
          nsDelay == null ? 0 : nsDelay / NANO_TO_SECS));
      if (stream != null) {
        InsightBuilder insight = new InsightBuilder();
        stream.appendTimeoutInsight(insight);
        buf.append(" ");
        buf.append(insight);
      }
      return DEADLINE_EXCEEDED.withDescription(buf.toString());
    }
  }

  @Nullable
  private Deadline effectiveDeadline() {
    // Call options and context are immutable, so we don't need to cache the deadline.
    return min(callOptions.getDeadline(), context.getDeadline());
  }

  @Nullable
  private static Deadline min(@Nullable Deadline deadline0, @Nullable Deadline deadline1) {
    if (deadline0 == null) {
      return deadline1;
    }
    if (deadline1 == null) {
      return deadline0;
    }
    return deadline0.minimum(deadline1);
  }

  @Override
  public void request(int numMessages) {
    try (TaskCloseable ignore = PerfMark.traceTask("ClientCall.request")) {
      PerfMark.attachTag(tag);
      checkState(stream != null, "Not started");
      checkArgument(numMessages >= 0, "Number requested must be non-negative");
      stream.request(numMessages);
    }
  }

  @Override
  public void cancel(@Nullable String message, @Nullable Throwable cause) {
    try (TaskCloseable ignore = PerfMark.traceTask("ClientCall.cancel")) {
      PerfMark.attachTag(tag);
      cancelInternal(message, cause);
    }
  }

  private void cancelInternal(@Nullable String message, @Nullable Throwable cause) {
    if (message == null && cause == null) {
      cause = new CancellationException("Cancelled without a message or cause");
      log.log(Level.WARNING, "Cancelling without a message or cause is suboptimal", cause);
    }
    if (cancelCalled) {
      return;
    }
    cancelCalled = true;
    try {
      // Cancel is called in exception handling cases, so it may be the case that the
      // stream was never successfully created or start has never been called.
      if (stream != null) {
        Status status = Status.CANCELLED;
        if (message != null) {
          status = status.withDescription(message);
        } else {
          status = status.withDescription("Call cancelled without message");
        }
        if (cause != null) {
          status = status.withCause(cause);
        }
        stream.cancel(status);
      }
    } finally {
      // start() might not have been called
      if (cancellationHandler != null) {
        cancellationHandler.tearDown();
      }
    }
  }

  @Override
  public void halfClose() {
    try (TaskCloseable ignore = PerfMark.traceTask("ClientCall.halfClose")) {
      PerfMark.attachTag(tag);
      halfCloseInternal();
    }
  }

  private void halfCloseInternal() {
    checkState(stream != null, "Not started");
    checkState(!cancelCalled, "call was cancelled");
    checkState(!halfCloseCalled, "call already half-closed");
    halfCloseCalled = true;
    stream.halfClose();
  }

  @Override
  public void sendMessage(ReqT message) {
    try (TaskCloseable ignore = PerfMark.traceTask("ClientCall.sendMessage")) {
      PerfMark.attachTag(tag);
      sendMessageInternal(message);
    }
  }

  private void sendMessageInternal(ReqT message) {
    checkState(stream != null, "Not started");
    checkState(!cancelCalled, "call was cancelled");
    checkState(!halfCloseCalled, "call was half-closed");
    try {
      if (stream instanceof RetriableStream) {
        @SuppressWarnings("unchecked")
        RetriableStream<ReqT> retriableStream = (RetriableStream<ReqT>) stream;
        retriableStream.sendMessage(message);
      } else {
        stream.writeMessage(method.streamRequest(message));
      }
    } catch (RuntimeException e) {
      stream.cancel(Status.CANCELLED.withCause(e).withDescription("Failed to stream message"));
      return;
    } catch (Error e) {
      stream.cancel(Status.CANCELLED.withDescription("Client sendMessage() failed with Error"));
      throw e;
    }
    // For unary requests, we don't flush since we know that halfClose should be coming soon. This
    // allows us to piggy-back the END_STREAM=true on the last message frame without opening the
    // possibility of broken applications forgetting to call halfClose without noticing.
    if (!unaryRequest) {
      stream.flush();
    }
  }

  @Override
  public void setMessageCompression(boolean enabled) {
    checkState(stream != null, "Not started");
    stream.setMessageCompression(enabled);
  }

  @Override
  public boolean isReady() {
    if (halfCloseCalled) {
      return false;
    }
    return stream.isReady();
  }

  @Override
  public Attributes getAttributes() {
    if (stream != null) {
      return stream.getAttributes();
    }
    return Attributes.EMPTY;
  }

  private void closeObserver(Listener<RespT> observer, Status status, Metadata trailers) {
    try {
      observer.onClose(status, trailers);
    } catch (RuntimeException ex) {
      log.log(Level.WARNING, "Exception thrown by onClose() in ClientCall", ex);
    }
  }

  @Override
  public String toString() {
    return MoreObjects.toStringHelper(this).add("method", method).toString();
  }

  private class ClientStreamListenerImpl implements ClientStreamListener {
    private final Listener<RespT> observer;
    private Status exceptionStatus;

    public ClientStreamListenerImpl(Listener<RespT> observer) {
      this.observer = checkNotNull(observer, "observer");
    }

    /**
     * Cancels call and schedules onClose() notification. May only be called from the application
     * thread.
     */
    private void exceptionThrown(Status status) {
      // Since each RPC can have its own executor, we can only call onClose() when we are sure there
      // will be no further callbacks. We set the status here and overwrite the onClose() details
      // when it arrives.
      exceptionStatus = status;
      stream.cancel(status);
    }

    @Override
    public void headersRead(final Metadata headers) {
      try (TaskCloseable ignore = PerfMark.traceTask("ClientStreamListener.headersRead")) {
        PerfMark.attachTag(tag);
        final Link link = PerfMark.linkOut();
        final class HeadersRead extends ContextRunnable {
          HeadersRead() {
            super(context);
          }

          @Override
          public void runInContext() {
            try (TaskCloseable ignore = PerfMark.traceTask("ClientCall$Listener.headersRead")) {
              PerfMark.attachTag(tag);
              PerfMark.linkIn(link);
              runInternal();
            }
          }

          private void runInternal() {
            if (exceptionStatus != null) {
              return;
            }
            try {
              observer.onHeaders(headers);
            } catch (Throwable t) {
              exceptionThrown(
                  Status.CANCELLED.withCause(t).withDescription("Failed to read headers"));
            }
          }
        }

        callExecutor.execute(new HeadersRead());
      }
    }

    @Override
    public void messagesAvailable(final MessageProducer producer) {
      try (TaskCloseable ignore = PerfMark.traceTask("ClientStreamListener.messagesAvailable")) {
        PerfMark.attachTag(tag);
        final Link link = PerfMark.linkOut();
        final class MessagesAvailable extends ContextRunnable {
          MessagesAvailable() {
            super(context);
          }

          @Override
          public void runInContext() {
            try (TaskCloseable ignore =
                     PerfMark.traceTask("ClientCall$Listener.messagesAvailable")) {
              PerfMark.attachTag(tag);
              PerfMark.linkIn(link);
              runInternal();
            }
          }

          private void runInternal() {
            if (exceptionStatus != null) {
              GrpcUtil.closeQuietly(producer);
              return;
            }
            try {
              InputStream message;
              while ((message = producer.next()) != null) {
                try {
                  observer.onMessage(method.parseResponse(message));
                } catch (Throwable t) {
                  GrpcUtil.closeQuietly(message);
                  throw t;
                }
                message.close();
              }
            } catch (Throwable t) {
              GrpcUtil.closeQuietly(producer);
              exceptionThrown(
                  Status.CANCELLED.withCause(t).withDescription("Failed to read message."));
            }
          }
        }

        callExecutor.execute(new MessagesAvailable());
      }
    }

    @Override
    public void closed(Status status, RpcProgress rpcProgress, Metadata trailers) {
      try (TaskCloseable ignore = PerfMark.traceTask("ClientStreamListener.closed")) {
        PerfMark.attachTag(tag);
        closedInternal(status, rpcProgress, trailers);
      }
    }

    private void closedInternal(
        Status status, @SuppressWarnings("unused") RpcProgress rpcProgress, Metadata trailers) {
      Deadline deadline = effectiveDeadline();
      if (status.getCode() == Status.Code.CANCELLED && deadline != null) {
        // When the server's deadline expires, it can only reset the stream with CANCEL and no
        // description. Since our timer may be delayed in firing, we double-check the deadline and
        // turn the failure into the likely more helpful DEADLINE_EXCEEDED status.
        if (deadline.isExpired()) {
          status = cancellationHandler.formatDeadlineExceededStatus();
          // Replace trailers to prevent mixing sources of status and trailers.
          trailers = new Metadata();
        }
      }
      final Status savedStatus = status;
      final Metadata savedTrailers = trailers;
      final Link link = PerfMark.linkOut();
      final class StreamClosed extends ContextRunnable {
        StreamClosed() {
          super(context);
        }

        @Override
        public void runInContext() {
          try (TaskCloseable ignore = PerfMark.traceTask("ClientCall$Listener.onClose")) {
            PerfMark.attachTag(tag);
            PerfMark.linkIn(link);
            runInternal();
          }
        }

        private void runInternal() {
          cancellationHandler.tearDown();
          Status status = savedStatus;
          Metadata trailers = savedTrailers;
          if (exceptionStatus != null) {
            // Ideally exceptionStatus == savedStatus, as exceptionStatus was passed to cancel().
            // However the cancel is racy and this closed() may have already been queued when the
            // cancellation occurred. Since other calls like onMessage() will throw away data if
            // exceptionStatus != null, it is semantically essential that we _not_ use a status
            // provided by the server.
            status = exceptionStatus;
            // Replace trailers to prevent mixing sources of status and trailers.
            trailers = new Metadata();
          }
          try {
            closeObserver(observer, status, trailers);
          } finally {
            channelCallsTracer.reportCallEnded(status.isOk());
          }
        }
      }

      callExecutor.execute(new StreamClosed());
    }

    @Override
    public void onReady() {
      if (method.getType().clientSendsOneMessage()) {
        return;
      }
      try (TaskCloseable ignore = PerfMark.traceTask("ClientStreamListener.onReady")) {
        PerfMark.attachTag(tag);
        final Link link = PerfMark.linkOut();

        final class StreamOnReady extends ContextRunnable {
          StreamOnReady() {
            super(context);
          }

          @Override
          public void runInContext() {
            try (TaskCloseable ignore = PerfMark.traceTask("ClientCall$Listener.onReady")) {
              PerfMark.attachTag(tag);
              PerfMark.linkIn(link);
              runInternal();
            }
          }

          private void runInternal() {
            if (exceptionStatus != null) {
              return;
            }
            try {
              observer.onReady();
            } catch (Throwable t) {
              exceptionThrown(
                  Status.CANCELLED.withCause(t).withDescription("Failed to call onReady."));
            }
          }
        }

        callExecutor.execute(new StreamOnReady());
      }
    }
  }
}
