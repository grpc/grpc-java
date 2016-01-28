/*
 * Copyright 2015, Google Inc. All rights reserved.
 *
 * Redistribution and use in source and binary forms, with or without
 * modification, are permitted provided that the following conditions are
 * met:
 *
 *    * Redistributions of source code must retain the above copyright
 * notice, this list of conditions and the following disclaimer.
 *    * Redistributions in binary form must reproduce the above
 * copyright notice, this list of conditions and the following disclaimer
 * in the documentation and/or other materials provided with the
 * distribution.
 *
 *    * Neither the name of Google Inc. nor the names of its
 * contributors may be used to endorse or promote products derived from
 * this software without specific prior written permission.
 *
 * THIS SOFTWARE IS PROVIDED BY THE COPYRIGHT HOLDERS AND CONTRIBUTORS
 * "AS IS" AND ANY EXPRESS OR IMPLIED WARRANTIES, INCLUDING, BUT NOT
 * LIMITED TO, THE IMPLIED WARRANTIES OF MERCHANTABILITY AND FITNESS FOR
 * A PARTICULAR PURPOSE ARE DISCLAIMED. IN NO EVENT SHALL THE COPYRIGHT
 * OWNER OR CONTRIBUTORS BE LIABLE FOR ANY DIRECT, INDIRECT, INCIDENTAL,
 * SPECIAL, EXEMPLARY, OR CONSEQUENTIAL DAMAGES (INCLUDING, BUT NOT
 * LIMITED TO, PROCUREMENT OF SUBSTITUTE GOODS OR SERVICES; LOSS OF USE,
 * DATA, OR PROFITS; OR BUSINESS INTERRUPTION) HOWEVER CAUSED AND ON ANY
 * THEORY OF LIABILITY, WHETHER IN CONTRACT, STRICT LIABILITY, OR TORT
 * (INCLUDING NEGLIGENCE OR OTHERWISE) ARISING IN ANY WAY OUT OF THE USE
 * OF THIS SOFTWARE, EVEN IF ADVISED OF THE POSSIBILITY OF SUCH DAMAGE.
 */

package io.grpc.internal;

import static com.google.common.base.Preconditions.checkNotNull;
import static com.google.common.base.Preconditions.checkState;
import static io.grpc.internal.GrpcUtil.ACCEPT_ENCODING_JOINER;
import static io.grpc.internal.GrpcUtil.ACCEPT_ENCODING_SPLITER;
import static io.grpc.internal.GrpcUtil.MESSAGE_ACCEPT_ENCODING_KEY;
import static io.grpc.internal.GrpcUtil.MESSAGE_ENCODING_KEY;

import com.google.common.annotations.VisibleForTesting;
import com.google.common.base.Throwables;

import io.grpc.Codec;
import io.grpc.CompressionNegotiator;
import io.grpc.Compressor;
import io.grpc.Context;
import io.grpc.Decompressor;
import io.grpc.Metadata;
import io.grpc.MethodDescriptor;
import io.grpc.MethodDescriptor.MethodType;
import io.grpc.ServerCall;
import io.grpc.Status;

import java.io.IOException;
import java.io.InputStream;
import java.util.HashSet;
import java.util.Set;
import java.util.concurrent.Future;

final class ServerCallImpl<ReqT, RespT> extends ServerCall<RespT> {
  private final ServerStream stream;
  private final MethodDescriptor<ReqT, RespT> method;
  private final Context.CancellableContext context;
  private final Metadata inboundHeaders;
  // state
  private CompressionNegotiator compressionNegotiator;
  private volatile boolean cancelled;
  private boolean sendHeadersCalled;
  private boolean requestCalled;
  private boolean closeCalled;


  ServerCallImpl(ServerStream stream, MethodDescriptor<ReqT, RespT> method,
      Context.CancellableContext context, CompressionNegotiator compressionNegotiator,
      Metadata inboundHeaders) {
    this.stream = stream;
    this.method = method;
    this.context = context;
    this.compressionNegotiator = compressionNegotiator;
    this.inboundHeaders = inboundHeaders;
  }

  @Override
  public void request(int numMessages) {
    if (!requestCalled) {
      requestCalled = true;
      Decompressor d = Codec.Identity.NONE;
      String messageEncoding = inboundHeaders.get(GrpcUtil.MESSAGE_ENCODING_KEY);
      if (messageEncoding != null) {
        d = compressionNegotiator.pickDecompressor(messageEncoding);
      }
      stream.setDecompressor(d);
    }
    stream.request(numMessages);
  }

  @Override
  public void sendHeaders(Metadata outboundHeaders) {
    checkState(!sendHeadersCalled, "sendHeaders has already been called");
    checkState(!closeCalled, "call is closed");
    // Don't check if sendMessage has been called, since it requires that sendHeaders was already
    // called.
    sendHeadersCalled = true;
    outboundHeaders.removeAll(MESSAGE_ACCEPT_ENCODING_KEY);
    Set<String> advertisedAcceptEncodings = compressionNegotiator.advertiseDecompressors();
    if (advertisedAcceptEncodings != null && !advertisedAcceptEncodings.isEmpty()) {
      outboundHeaders.put(
          MESSAGE_ACCEPT_ENCODING_KEY, ACCEPT_ENCODING_JOINER.join(advertisedAcceptEncodings));
    }

    outboundHeaders.removeAll(MESSAGE_ENCODING_KEY);
    Compressor c = Codec.Identity.NONE;
    String messageAcceptEncoding = inboundHeaders.get(GrpcUtil.MESSAGE_ACCEPT_ENCODING_KEY);
    if (messageAcceptEncoding != null) {
      Set<String> encodings =
          new HashSet<String>(ACCEPT_ENCODING_SPLITER.splitToList(messageAcceptEncoding));
      c = checkNotNull(
          compressionNegotiator.pickCompressor(encodings),
          "Can't use null compressor");
    }
    stream.setCompressor(c);
    if (c != Codec.Identity.NONE) {
      outboundHeaders.put(MESSAGE_ENCODING_KEY, c.getMessageEncoding());
    }

    stream.writeHeaders(outboundHeaders);
  }

  @Override
  public void sendMessage(RespT message) {
    checkState(sendHeadersCalled, "sendHeaders has not been called");
    checkState(!closeCalled, "call is closed");
    try {
      InputStream resp = method.streamResponse(message);
      stream.writeMessage(resp);
      stream.flush();
    } catch (Throwable t) {
      close(Status.fromThrowable(t), new Metadata());
      throw Throwables.propagate(t);
    }
  }

  @Override
  public void setMessageCompression(boolean enable) {
    stream.setMessageCompression(enable);
  }

  @Override
  public void overrideCompressionNegotiator(CompressionNegotiator negotiator) {
    this.compressionNegotiator = checkNotNull(negotiator, "negotiator");
  }

  @Override
  public boolean isReady() {
    return stream.isReady();
  }

  @Override
  public void close(Status status, Metadata trailers) {
    try {
      checkState(!closeCalled, "call already closed");
      closeCalled = true;
      stream.close(status, trailers);
    } finally {
      if (status.getCode() == Status.Code.OK) {
        context.cancel(null);
      } else {
        context.cancel(status.getCause() != null ? status.getCause() : status.asRuntimeException());
      }
    }
  }

  @Override
  public boolean isCancelled() {
    return cancelled;
  }

  ServerStreamListener newServerStreamListener(ServerCall.Listener<ReqT> listener,
      Future<?> timeout) {
    return new ServerStreamListenerImpl<ReqT>(this, listener, timeout);
  }

  /**
   * All of these callbacks are assumed to called on an application thread, and the caller is
   * responsible for handling thrown exceptions.
   */
  @VisibleForTesting
  static final class ServerStreamListenerImpl<ReqT> implements ServerStreamListener {
    private final ServerCallImpl<ReqT, ?> call;
    private final ServerCall.Listener<ReqT> listener;
    private final Future<?> timeout;
    private boolean messageReceived;

    public ServerStreamListenerImpl(
        ServerCallImpl<ReqT, ?> call, ServerCall.Listener<ReqT> listener, Future<?> timeout) {
      this.call = checkNotNull(call, "call");
      this.listener = checkNotNull(listener, "listener must not be null");
      this.timeout = checkNotNull(timeout, "timeout");
    }

    @Override
    public void messageRead(final InputStream message) {
      try {
        if (call.cancelled) {
          return;
        }
        // Special case for unary calls.
        if (messageReceived && call.method.getType() == MethodType.UNARY) {
          call.stream.close(Status.INVALID_ARGUMENT.withDescription(
                  "More than one request messages for unary call or server streaming call"),
              new Metadata());
          return;
        }
        messageReceived = true;

        listener.onMessage(call.method.parseRequest(message));
      } finally {
        try {
          message.close();
        } catch (IOException e) {
          throw new RuntimeException(e);
        }
      }
    }

    @Override
    public void halfClosed() {
      if (call.cancelled) {
        return;
      }

      listener.onHalfClose();
    }

    @Override
    public void closed(Status status) {
      timeout.cancel(true);
      if (status.isOk()) {
        listener.onComplete();
      } else {
        call.cancelled = true;
        listener.onCancel();
      }
    }

    @Override
    public void onReady() {
      if (call.cancelled) {
        return;
      }
      listener.onReady();
    }
  }
}
