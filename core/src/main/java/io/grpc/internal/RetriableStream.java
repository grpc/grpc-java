/*
 * Copyright 2017, gRPC Authors All rights reserved.
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

import static io.grpc.internal.Substream.Progress.CANCELLED;
import static io.grpc.internal.Substream.Progress.START;

import io.grpc.Attributes;
import io.grpc.CallOptions;
import io.grpc.Compressor;
import io.grpc.Context;
import io.grpc.Context.Key;
import io.grpc.DecompressorRegistry;
import io.grpc.Metadata;
import io.grpc.MethodDescriptor;
import io.grpc.Status;
import io.grpc.internal.ClientCallImpl.ClientTransportProvider;
import java.io.InputStream;

/**
 * A logical {@link ClientStream} that is retriable. It contains one active {@link Substream}
 * representing the current retry attempt. The first method to be called after constructor is {@code
 * start(ClientStreamListener masterListener)}.
 */
final class RetriableStream<ReqT> implements ClientStream {
  static final Key<Substream> SUBSTREAM_KEY = Context.<Substream>key("substream");
  static final Key<RetryOrHedgingBuffer<?>> BUFFER_KEY =
      Context.<RetryOrHedgingBuffer<?>>key("buffer");
  private final MethodDescriptor<ReqT, ?> method;
  private final RetryOrHedgingBuffer<ReqT> buffer;
  private final CallOptions callOptions;
  private final Metadata headers;
  private final ClientTransportProvider clientTransportProvider;
  private final DelayedClientTransport delayedClientTransport;
  private final Context context;
  /** Lock for synchronizing {@link #retry()} and {@link #cancel(Status)}. */
  private final Object lock = new Object();
  /** No need to synchronize its callbacks b/c no hedging involved. */
  private ClientStreamListener masterListener;

  private Status cancellingStatus;
  private Substream substream;

  RetriableStream(
      MethodDescriptor<ReqT, ?> method,
      CallOptions callOptions,
      Metadata headers,
      ClientTransportProvider clientTransportProvider,
      Context context) {
    this.method = method;
    buffer = new RetryOrHedgingBuffer<ReqT>(method);
    this.callOptions = callOptions;
    this.headers = headers;
    this.clientTransportProvider = clientTransportProvider;
    delayedClientTransport = clientTransportProvider.getDelayedTransport();
    this.context = context;

    // This is the first RPC attempt.
    substream = new RetrySubstream();
  }

  /** Requests if committed, or buffers it first and then requests on the current substream. */
  @Override
  public void request(int numMessages) {
    if (buffer.enqueueRequest(numMessages)) {
      buffer.resume(substream);
    } else {
      clientStream().request(numMessages);
    }
  }

  /**
   * Do not use it directly. Use {@link #sendMessage(ReqT)} instead because we don't use InputStream
   * for buffering.
   */
  @Override
  public void writeMessage(InputStream message) {
    // TODO(notcarl): Find out if streamRequest needs to be closed.
    clientStream().writeMessage(message);
  }

  /**
   * Sends out the message if committed, or buffers it first and then sends it out on the current
   * substream.
   */
  void sendMessage(ReqT message) {
    if (buffer.enqueueMessage(message)) {
      buffer.resume(substream);
    } else {
      writeMessage(method.streamRequest(message));
    }
  }

  /** Flushes if committed, or buffers it first and then flushes on the current substream. */
  @Override
  public void flush() {
    if (buffer.enqueueFlush()) {
      buffer.resume(substream);
    } else {
      clientStream().flush();
    }
  }

  @Override
  public boolean isReady() {
    return clientStream().isReady();
  }

  /**
   * Sets the compressor if committed, or buffers it first and then sets it on the current
   * substream.
   */
  @Override
  public void setCompressor(Compressor compressor) {
    if (buffer.enqueueCompressor(compressor)) {
      buffer.resume(substream);
    } else {
      clientStream().setCompressor(compressor);
    }
  }

  /**
   * Sets the fullStreamDecompression if committed, or buffers it first and then sets it on the
   * current substream.
   */
  @Override
  public void setFullStreamDecompression(boolean fullStreamDecompression) {
    if (buffer.enqueueFullStreamDecompression(fullStreamDecompression)) {
      buffer.resume(substream);
    } else {
      clientStream().setFullStreamDecompression(fullStreamDecompression);
    }
  }

  /**
   * Set enable message compression if committed, or buffers it first and then sets it on the
   * current substream.
   */
  @Override
  public void setMessageCompression(boolean enable) {
    if (buffer.enqueueMessageCompression(enable)) {
      buffer.resume(substream);
    } else {
      clientStream().setMessageCompression(enable);
    }
  }

  @Override
  public void cancel(Status reason) {
    synchronized (lock) {
      cancellingStatus = reason;
    }
    substream.updateProgress(CANCELLED);
    buffer.commit(substream);
    substream.clientStream().cancel(cancellingStatus);
    delayedClientTransport.removeRetryOrHedgingStream(this);
  }

  private boolean cancelled() {
    return cancellingStatus != null;
  }

  /** Half-closes if committed, or buffers it first and then half-closes the current substream. */
  @Override
  public void halfClose() {
    if (buffer.enqueueHalfClose()) {
      buffer.resume(substream);
    } else {
      clientStream().halfClose();
    }
  }

  @Override
  public void setAuthority(String authority) {
    buffer.enqueueAuthority(authority);
  }

  @Override
  public void setDecompressorRegistry(DecompressorRegistry decompressorRegistry) {
    buffer.enqueueDecompressorRegistry(decompressorRegistry);
  }

  /**
   * Sets the max inbound message size if committed, or buffers it first and then sets it on the
   * current substream.
   */
  @Override
  public void setMaxInboundMessageSize(int maxSize) {
    if (buffer.enqueueMaxInboundMessageSize(maxSize)) {
      buffer.resume(substream);
    } else {
      clientStream().setMaxInboundMessageSize(maxSize);
    }
  }

  /**
   * Sets the max outbound message size if committed, or buffers it first and then sets it on the
   * current substream.
   */
  @Override
  public void setMaxOutboundMessageSize(int maxSize) {
    if (buffer.enqueueMaxOutboundMessageSize(maxSize)) {
      buffer.resume(substream);
    } else {
      clientStream().setMaxOutboundMessageSize(maxSize);
    }
  }

  /** Starts the first PRC attempt, and in the mean time also buffer the task. */
  @Override
  public void start(ClientStreamListener masterListener) {
    this.masterListener = masterListener;
    delayedClientTransport.addRetryOrHedgingStream(this);

    buffer.enqueueStart();
    buffer.resume(substream);
  }

  @Override
  public Attributes getAttributes() {
    return clientStream().getAttributes();
  }

  private ClientStream clientStream() {
    return substream.clientStream();
  }

  // TODO: implement retry policy.
  // Retry policy is obtained from the combination of the name resolver plus channel builder, and
  // passed all the way down to this class.
  private boolean shouldRetry() {
    return false;
  }

  private void retry() {
    synchronized (lock) {
      if (cancelled()) {
        return;
      }
    }

    // This is a retry RPC attempt.
    // This must be outside of the lock b/c it is expensive.
    Substream sub = new RetrySubstream(); // TODO: update "grpc-retry-attempts" header

    synchronized (lock) {
      if (!cancelled()) {
        substream = sub;
      }
      // else: Do not update substream. The subsequent buffer.resume(sub) will run start() first,
      // which cancels this orphan sub immediately.
    }

    // This must be outside of the lock b/c it is expensive.
    buffer.resume(sub);
  }

  private final class RetrySubstream implements Substream {

    final ClientStream stream;
    // @GuadedBy("this")
    Progress progress = START;
    // @GuadedBy("this")
    boolean isResuming;

    RetrySubstream() {
      ClientTransport transport =
          clientTransportProvider.get(new PickSubchannelArgsImpl(method, headers, callOptions));
      // Must create a new stream in the given context.
      Context context =
          RetriableStream.this.context
              // The line below is a hacky way to pass substream and buffer to DelayedStream2.
              // Try alternatives, if a better way exists.
              .withValues(SUBSTREAM_KEY, this, BUFFER_KEY, buffer);
      Context origContext = context.attach();
      try {
        stream = transport.newStream(method, headers, callOptions);
      } finally {
        context.detach(origContext);
      }
    }

    @Override
    public void start() {
      stream.start(new RetrySublistener());
      if (cancelled()) {
        // In case this is the orphan sub created in retry().
        stream.cancel(cancellingStatus);
      }
    }

    @Override
    public ClientStream clientStream() {
      return stream;
    }

    @Override
    // @GuadedBy("this")
    public boolean isPending() {
      if (stream instanceof DelayedStream2) {
        return !((DelayedStream2) stream).isPassThrough();
      }
      return false;
    }

    @Override
    // @GuadedBy("this")
    public boolean isResuming() {
      return isResuming;
    }

    @Override
    // @GuadedBy("this")
    public void setResuming(boolean resuming) {
      this.isResuming = resuming;
    }

    @Override
    public synchronized Progress progress() {
      return progress;
    }

    @Override
    public synchronized void updateProgress(Progress progress) {
      if (this.progress == CANCELLED) {
        return;
      }
      this.progress = progress;
    }

    final class RetrySublistener implements ClientStreamListener {
      private boolean commited;

      @Override
      public void headersRead(Metadata headers) {
        if (!commited) {
          buffer.commit(RetrySubstream.this);
          commited = true;
          delayedClientTransport.removeRetryOrHedgingStream(RetriableStream.this);
        }
        masterListener.headersRead(headers);
      }

      @Override
      public void closed(Status status, Metadata trailers) {
        if (!commited && !cancelled() && shouldRetry()) {
          // TODO: backoff
          retry();
        } else {
          masterListener.closed(status, trailers);
        }
      }

      @Override
      public void messagesAvailable(MessageProducer producer) {
        masterListener.messagesAvailable(producer);
      }

      @Override
      public void onReady() {
        masterListener.onReady();
      }
    }
  }
}
