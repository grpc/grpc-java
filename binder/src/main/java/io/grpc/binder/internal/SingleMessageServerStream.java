/*
 * Copyright 2020 The gRPC Authors
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

package io.grpc.binder.internal;

import io.grpc.Attributes;
import io.grpc.Compressor;
import io.grpc.Decompressor;
import io.grpc.Metadata;
import io.grpc.Status;
import io.grpc.StatusException;
import io.grpc.internal.ServerStream;
import io.grpc.internal.ServerStreamListener;
import io.grpc.internal.StatsTraceContext;
import java.io.InputStream;
import javax.annotation.Nullable;

/**
 * The server side of a single RPC, which sends a single response message.
 *
 * <p>An instance of this class is effectively a go-between, receiving messages from the gRPC
 * ServerCall instance (via calls on the ServerStream interface we implement), and sending them out
 * on the transport, as well as receiving messages from the transport, and passing the resultant
 * data back to the gRPC ServerCall instance (via calls on the ServerStreamListener instance we're
 * given).
 *
 * <p>These two communication directions are largely independent of each other, with the {@link
 * Outbound} handling the gRPC to transport direction, and the {@link Inbound} class handling
 * transport to gRPC direction.
 *
 * <p>Since the Inbound and Outbound halves are largely independent, their state is also
 * synchronized independently.
 */
final class SingleMessageServerStream implements ServerStream {

  private final Inbound.ServerInbound inbound;
  private final Outbound.ServerOutbound outbound;
  private final Attributes attributes;

  @Nullable private Metadata pendingHeaders;
  @Nullable private InputStream pendingSingleMessage;

  SingleMessageServerStream(
      Inbound.ServerInbound inbound, Outbound.ServerOutbound outbound, Attributes attributes) {
    this.inbound = inbound;
    this.outbound = outbound;
    this.attributes = attributes;
  }

  @Override
  public void setListener(ServerStreamListener listener) {
    synchronized (inbound) {
      inbound.init(outbound, listener);
    }
  }

  @Override
  public boolean isReady() {
    return outbound.isReady();
  }

  @Override
  public void request(int numMessages) {
    synchronized (inbound) {
      inbound.requestMessages(numMessages);
    }
  }

  @Override
  public void writeHeaders(Metadata headers) {
    pendingHeaders = headers;
  }

  @Override
  public void writeMessage(InputStream message) {
    if (pendingSingleMessage != null) {
      synchronized (inbound) {
        inbound.closeAbnormal(Status.INTERNAL.withDescription("too many messages"));
      }
    } else {
      pendingSingleMessage = message;
    }
  }

  @Override
  public void close(Status status, Metadata trailers) {
    try {
      synchronized (outbound) {
        outbound.sendSingleMessageAndClose(pendingHeaders, pendingSingleMessage, status, trailers);
      }
      synchronized (inbound) {
        inbound.onCloseSent(status);
      }
    } catch (StatusException se) {
      synchronized (inbound) {
        inbound.closeAbnormal(se.getStatus());
      }
    }
  }

  @Override
  public void cancel(Status status) {
    synchronized (inbound) {
      inbound.closeOnCancel(status);
    }
  }

  @Override
  public StatsTraceContext statsTraceContext() {
    return outbound.getStatsTraceContext();
  }

  @Override
  public Attributes getAttributes() {
    return attributes;
  }

  @Nullable
  @Override
  public String getAuthority() {
    return attributes.get(BinderTransport.SERVER_AUTHORITY);
  }

  @Override
  public String toString() {
    return "SingleMessageServerStream[" + inbound + "/" + outbound + "]";
  }

  // =====================
  // Misc stubbed & unsupported methods.

  @Override
  public final void flush() {
    // Ignore.
  }

  @Override
  public final void setCompressor(Compressor compressor) {
    // Ignore.
  }

  @Override
  public final void setMessageCompression(boolean enable) {
    // Ignore.
  }

  @Override
  public void setDecompressor(Decompressor decompressor) {
    // Ignore.
  }

  @Override
  public void optimizeForDirectExecutor() {
    // Ignore.
  }

  @Override
  public int streamId() {
    return -1;
  }
}
