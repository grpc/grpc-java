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
import static io.grpc.Contexts.statusFromCancelled;
import static io.grpc.Status.DEADLINE_EXCEEDED;
import static io.grpc.internal.GrpcUtil.CONTENT_ACCEPT_ENCODING_KEY;
import static io.grpc.internal.GrpcUtil.CONTENT_ENCODING_KEY;
import static io.grpc.internal.GrpcUtil.MESSAGE_ACCEPT_ENCODING_KEY;
import static io.grpc.internal.GrpcUtil.MESSAGE_ENCODING_KEY;
import static java.lang.Math.max;

import com.google.common.annotations.VisibleForTesting;
import com.google.common.base.MoreObjects;
import io.grpc.Attributes;
import io.grpc.CallOptions;
import io.grpc.ClientCall;
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
import java.io.InputStream;
import java.nio.charset.Charset;
import java.util.Locale;
import java.util.concurrent.CancellationException;
import java.util.concurrent.Executor;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
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

  private final MethodDescriptor<ReqT, RespT> method;
  private final Tag tag;
  private final Executor callExecutor;
  private final boolean callExecutorIsDirect;
  private final CallTracer channelCallsTracer;
  private final Context context;
  private volatile ScheduledFuture<?> deadlineCancellationFuture;
  private final boolean unaryRequest;
  private CallOptions callOptions;
  private ClientStream stream;
  private volatile boolean cancelListenersShouldBeRemoved;
  private boolean cancelCalled;
  private boolean halfCloseCalled;
  private final ClientStreamProvider clientStreamProvider;
  private final ContextCancellationListener cancellationListener =
      new ContextCancellationListener();
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

  private final class ContextCancellationListener implements CancellationListener {
    @Override
    public void cancelled(Context context) {
      stream.cancel(statusFromCancelled(context));
    }
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
    PerfMark.startTask("ClientCall.start", tag);
    try {
      startInternal(observer, headers);
    } finally {
      PerfMark.stopTask("ClientCall.start", tag);
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
    boolean deadlineExceeded = effectiveDeadline != null && effectiveDeadline.isExpired();
    if (!deadlineExceeded) {
      logIfContextNarrowedTimeout(
          effectiveDeadline, context.getDeadline(), callOptions.getDeadline());
      stream = clientStreamProvider.newStream(method, callOptions, headers, context);
    } else {
      stream = new FailingClientStream(
          DEADLINE_EXCEEDED.withDescription(
              "ClientCall started after deadline exceeded: " + effectiveDeadline));
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
    context.addListener(cancellationListener, directExecutor());
    if (effectiveDeadline != null
        // If the context has the effective deadline, we don't need to schedule an extra task.
        && !effectiveDeadline.equals(context.getDeadline())
        // If the channel has been terminated, we don't need to schedule an extra task.
        && deadlineCancellationExecutor != null) {
      deadlineCancellationFuture = startDeadlineTimer(effectiveDeadline);
    }
    if (cancelListenersShouldBeRemoved) {
      // Race detected! ClientStreamListener.closed may have been called before
      // deadlineCancellationFuture was set / context listener added, thereby preventing the future
      // and listener from being cancelled. Go ahead and cancel again, just to be sure it
      // was cancelled.
      removeContextListenerAndCancelDeadlineFuture();
    }
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

  private static void logIfContextNarrowedTimeout(
      Deadline effectiveDeadline, @Nullable Deadline outerCallDeadline,
      @Nullable Deadline callDeadline) {
    if (!log.isLoggable(Level.FINE) || effectiveDeadline == null
        || !effectiveDeadline.equals(outerCallDeadline)) {
      return;
    }

    long effectiveTimeout = max(0, effectiveDeadline.timeRemaining(TimeUnit.NANOSECONDS));
    StringBuilder builder = new StringBuilder(String.format(
        "Call timeout set to '%d' ns, due to context deadline.", effectiveTimeout));
    if (callDeadline == null) {
      builder.append(" Explicit call timeout was not set.");
    } else {
      long callTimeout = callDeadline.timeRemaining(TimeUnit.NANOSECONDS);
      builder.append(String.format(" Explicit call timeout was '%d' ns.", callTimeout));
    }

    log.fine(builder.toString());
  }

  private void removeContextListenerAndCancelDeadlineFuture() {
    context.removeListener(cancellationListener);
    ScheduledFuture<?> f = deadlineCancellationFuture;
    if (f != null) {
      f.cancel(false);
    }
  }

  private class DeadlineTimer implements Runnable {
    private final long remainingNanos;

    DeadlineTimer(long remainingNanos) {
      this.remainingNanos = remainingNanos;
    }

    @Override
    public void run() {
      InsightBuilder insight = new InsightBuilder();
      stream.appendTimeoutInsight(insight);
      // DelayedStream.cancel() is safe to call from a thread that is different from where the
      // stream is created.
      long seconds = Math.abs(remainingNanos) / TimeUnit.SECONDS.toNanos(1);
      long nanos = Math.abs(remainingNanos) % TimeUnit.SECONDS.toNanos(1);

      StringBuilder buf = new StringBuilder();
      buf.append("deadline exceeded after ");
      if (remainingNanos < 0) {
        buf.append('-');
      }
      buf.append(seconds);
      buf.append(String.format(Locale.US, ".%09d", nanos));
      buf.append("s. ");
      buf.append(insight);
      stream.cancel(DEADLINE_EXCEEDED.augmentDescription(buf.toString()));
    }
  }

  private ScheduledFuture<?> startDeadlineTimer(Deadline deadline) {
    long remainingNanos = deadline.timeRemaining(TimeUnit.NANOSECONDS);
    return deadlineCancellationExecutor.schedule(
        new LogExceptionRunnable(
            new DeadlineTimer(remainingNanos)), remainingNanos, TimeUnit.NANOSECONDS);
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
    PerfMark.startTask("ClientCall.request", tag);
    try {
      checkState(stream != null, "Not started");
      checkArgument(numMessages >= 0, "Number requested must be non-negative");
      stream.request(numMessages);
    } finally {
      PerfMark.stopTask("ClientCall.request", tag);
    }
  }

  @Override
  public void cancel(@Nullable String message, @Nullable Throwable cause) {
    PerfMark.startTask("ClientCall.cancel", tag);
    try {
      cancelInternal(message, cause);
    } finally {
      PerfMark.stopTask("ClientCall.cancel", tag);
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
      removeContextListenerAndCancelDeadlineFuture();
    }
  }

  @Override
  public void halfClose() {
    PerfMark.startTask("ClientCall.halfClose", tag);
    try {
      halfCloseInternal();
    } finally {
      PerfMark.stopTask("ClientCall.halfClose", tag);
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
    PerfMark.startTask("ClientCall.sendMessage", tag);
    try {
      sendMessageInternal(message);
    } finally {
      PerfMark.stopTask("ClientCall.sendMessage", tag);
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
    observer.onClose(status, trailers);
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
      PerfMark.startTask("ClientStreamListener.headersRead", tag);
      final Link link = PerfMark.linkOut();

      final class HeadersRead extends ContextRunnable {
        HeadersRead() {
          super(context);
        }

        @Override
        public void runInContext() {
          PerfMark.startTask("ClientCall$Listener.headersRead", tag);
          PerfMark.linkIn(link);
          try {
            runInternal();
          } finally {
            PerfMark.stopTask("ClientCall$Listener.headersRead", tag);
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

      try {
        callExecutor.execute(new HeadersRead());
      } finally {
        PerfMark.stopTask("ClientStreamListener.headersRead", tag);
      }
    }

    @Override
    public void messagesAvailable(final MessageProducer producer) {
      PerfMark.startTask("ClientStreamListener.messagesAvailable", tag);
      final Link link = PerfMark.linkOut();

      final class MessagesAvailable extends ContextRunnable {
        MessagesAvailable() {
          super(context);
        }

        @Override
        public void runInContext() {
          PerfMark.startTask("ClientCall$Listener.messagesAvailable", tag);
          PerfMark.linkIn(link);
          try {
            runInternal();
          } finally {
            PerfMark.stopTask("ClientCall$Listener.messagesAvailable", tag);
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

      try {
        callExecutor.execute(new MessagesAvailable());
      } finally {
        PerfMark.stopTask("ClientStreamListener.messagesAvailable", tag);
      }
    }

    @Override
    public void closed(Status status, Metadata trailers) {
      closed(status, RpcProgress.PROCESSED, trailers);
    }

    @Override
    public void closed(Status status, RpcProgress rpcProgress, Metadata trailers) {
      PerfMark.startTask("ClientStreamListener.closed", tag);
      try {
        closedInternal(status, rpcProgress, trailers);
      } finally {
        PerfMark.stopTask("ClientStreamListener.closed", tag);
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
          InsightBuilder insight = new InsightBuilder();
          stream.appendTimeoutInsight(insight);
          status = DEADLINE_EXCEEDED.augmentDescription(
              "ClientCall was cancelled at or after deadline. " + insight);
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
          PerfMark.startTask("ClientCall$Listener.onClose", tag);
          PerfMark.linkIn(link);
          try {
            runInternal();
          } finally {
            PerfMark.stopTask("ClientCall$Listener.onClose", tag);
          }
        }

        private void runInternal() {
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
          cancelListenersShouldBeRemoved = true;
          try {
            closeObserver(observer, status, trailers);
          } finally {
            removeContextListenerAndCancelDeadlineFuture();
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

      PerfMark.startTask("ClientStreamListener.onReady", tag);
      final Link link = PerfMark.linkOut();

      final class StreamOnReady extends ContextRunnable {
        StreamOnReady() {
          super(context);
        }

        @Override
        public void runInContext() {
          PerfMark.startTask("ClientCall$Listener.onReady", tag);
          PerfMark.linkIn(link);
          try {
            runInternal();
          } finally {
            PerfMark.stopTask("ClientCall$Listener.onReady", tag);
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

      try {
        callExecutor.execute(new StreamOnReady());
      } finally {
        PerfMark.stopTask("ClientStreamListener.onReady", tag);
      }
    }
  }
}
