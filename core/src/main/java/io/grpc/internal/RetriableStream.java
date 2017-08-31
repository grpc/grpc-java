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

import static io.grpc.internal.Substream.Progress.START;

import io.grpc.Attributes;
import io.grpc.CallOptions;
import io.grpc.Compressor;
import io.grpc.Context;
import io.grpc.DecompressorRegistry;
import io.grpc.Metadata;
import io.grpc.MethodDescriptor;
import io.grpc.Status;
import io.grpc.internal.ClientCallImpl.ClientTransportProvider;
import java.io.InputStream;

/**
 * A logical {@link ClientStream} that is retriable. It contains one active {@link
 * Substream} representing the current retry attempt.
 */
final class RetriableStream<ReqT> implements ClientStream {
  private final MethodDescriptor<ReqT, ?> method;
  private final RetryOrHedgingBuffer<ReqT> buffer;
  private final Compressor compressor;
  private final DecompressorRegistry decompressorRegistry;
  private final CallOptions callOptions;
  private final Metadata headers;
  private final ClientTransportProvider clientTransportProvider;
  private final Context context;
  private ClientStreamListener masterListener;

  private final Object lock = new Object();

  private boolean cancelled;
  private Status cancellingStatus;
  private Substream substream;

  RetriableStream(
      MethodDescriptor<ReqT, ?> method, RetryOrHedgingBuffer<ReqT> buffer, Compressor compressor,
      DecompressorRegistry decompressorRegistry, CallOptions callOptions, Metadata headers,
      ClientTransportProvider clientTransportProvider, Context context) {
    this.method = method;
    this.buffer = buffer;
    this.compressor = compressor;
    this.decompressorRegistry = decompressorRegistry;
    this.callOptions = callOptions;
    this.headers = headers;
    this.clientTransportProvider = clientTransportProvider;
    this.context = context;
  }

  @Override
  public void request(int numMessages) {
    if (buffer.enqueueRequest(numMessages)) {
      buffer.resume(substream);
    } else {
      clientStream().request(numMessages);
    }
  }

  /**
   * Do not use it. Use {@link #sendMessage(ReqT)} instead because we don't use InputStream for
   * buffering.
   */
  @Override
  public void writeMessage(InputStream message) {
    throw new UnsupportedOperationException("unsupported");
  }

  /**
   * Sends out the message, or buffers it first and then sends it out.
   */
  void sendMessage(ReqT message) {
    if (buffer.enqueueMessage(message)) {
      buffer.resume(substream);
    } else {
      // TODO(notcarl): Find out if streamRequest needs to be closed.
      clientStream().writeMessage(method.streamRequest(message));
    }
  }

  @Override
  public void flush() {
    clientStream().flush();
  }

  @Override
  public boolean isReady() {
    return clientStream().isReady();
  }

  @Override
  public void setCompressor(Compressor compressor) {
    throw new UnsupportedOperationException("unsupported");
  }

  @Override
  public void setMessageCompression(boolean enable) {
    clientStream().setMessageCompression(enable);
  }

  @Override
  public void cancel(Status reason) {
    synchronized (lock) {
      cancelled = true;
      cancellingStatus = reason;
    }
    buffer.commit(substream);
    clientStream().cancel(cancellingStatus);
  }

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
    throw new UnsupportedOperationException("unsupported");
  }

  @Override
  public void setDecompressorRegistry(DecompressorRegistry decompressorRegistry) {
    throw new UnsupportedOperationException("unsupported");
  }

  @Override
  public void start(ClientStreamListener masterListener) {
    this.masterListener = masterListener;
    substream = new RetrySubstream();
    substream.start();
  }

  private void retry() {
    if (cancelled) {
      return;
    }
    // TODO: update "grpc-retry-attempts" header
    Substream sub = new RetrySubstream();
    buffer.replay(sub);

    synchronized (lock) {
      if (cancelled) {
        sub.clientStream().cancel(cancellingStatus);
        return;
      }
      this.substream = sub;
    }

    buffer.resume(sub);
  }

  @Override
  public void setMaxInboundMessageSize(int maxSize) {
    throw new UnsupportedOperationException("unsupported");
  }

  @Override
  public void setMaxOutboundMessageSize(int maxSize) {
    throw new UnsupportedOperationException("unsupported");
  }

  @Override
  public Attributes getAttributes() {
    return clientStream().getAttributes();
  }

  private ClientStream clientStream() {
    return substream.clientStream();
  }

  private final class RetrySubstream implements Substream {

    ClientStream stream;
    Progress progress = START;


    @Override
    public void start() {
      // TODO: add buffer size stream tracer factory in callOptions.
      ClientTransport transport = clientTransportProvider.get(
          new PickSubchannelArgsImpl(method, headers, callOptions));
      Context origContext = context.attach();
      try {
        stream = transport.newStream(method, headers, callOptions);
      } finally {
        context.detach(origContext);
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
      stream.setCompressor(compressor);
      stream.setDecompressorRegistry(decompressorRegistry);

      stream.start(new RetrySublistener());
    }

    @Override
    public ClientStream clientStream() {
      return stream;
    }

    @Override
    public Progress progress() {
      return progress;
    }

    @Override
    public void updateProgress(Progress progress) {
      this.progress = progress;
    }

    final class RetrySublistener implements ClientStreamListener {
      private boolean commited;

      @Override
      public void headersRead(Metadata headers) {
        if (!commited) {
          buffer.commit(RetrySubstream.this);
          commited = true;
        }
        masterListener.headersRead(headers);
      }

      @Override
      public void closed(Status status, Metadata trailers) {
        if (!commited && !cancelled && shouldRetry()) {
          // TODO: backoff
          retry();
        } else {
          masterListener.closed(status, trailers);
        }
      }

      @Override
      public void messagesAvailable(MessageProducer producer) {
        if (!commited) {
          buffer.commit(RetrySubstream.this);
          commited = true;
        }
        masterListener.messagesAvailable(producer);
      }

      @Override
      public void onReady() {
        masterListener.onReady();
      }
    }
  }

  // TODO: implement retry policy.
  // Retry policy is obtained from the combination of the name resolver plus channel builder, and
  // passed all the way down to this class.
  private boolean shouldRetry() {
    return false;
  }
}
