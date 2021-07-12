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
import io.grpc.Deadline;
import io.grpc.DecompressorRegistry;
import io.grpc.Status;
import io.grpc.StatusException;
import io.grpc.internal.ClientStream;
import io.grpc.internal.ClientStreamListener;
import io.grpc.internal.InsightBuilder;
import java.io.InputStream;
import javax.annotation.Nonnull;

/**
 * The client side of a single RPC, which sends a stream of request messages.
 *
 * <p>An instance of this class is effectively a go-between, receiving messages from the gRPC
 * ClientCall instance (via calls on the ClientStream interface we implement), and sending them out
 * on the transport, as well as receiving messages from the transport, and passing the resultant
 * data back to the gRPC ClientCall instance (via calls on the ClientStreamListener instance we're
 * given).
 *
 * <p>These two communication directions are largely independent of each other, with the {@link
 * Outbound} handling the gRPC to transport direction, and the {@link Inbound} class handling
 * transport to gRPC direction.
 *
 * <p>Since the Inbound and Outbound halves are largely independent, their state is also
 * synchronized independently.
 */
final class MultiMessageClientStream implements ClientStream {

  private final Inbound.ClientInbound inbound;
  private final Outbound.ClientOutbound outbound;
  private final Attributes attributes;

  MultiMessageClientStream(
      Inbound.ClientInbound inbound, Outbound.ClientOutbound outbound, Attributes attributes) {
    this.inbound = inbound;
    this.outbound = outbound;
    this.attributes = attributes;
  }

  @Override
  public void start(ClientStreamListener listener) {
    synchronized (inbound) {
      inbound.init(outbound, listener);
    }
    if (outbound.isReady()) {
      listener.onReady();
    }
    try {
      synchronized (outbound) {
        // The ClientStream contract promises no more header changes after start().
        outbound.onPrefixReady();
        outbound.send();
      }
    } catch (StatusException se) {
      synchronized (inbound) {
        inbound.closeAbnormal(se.getStatus());
      }
    }
  }

  @Override
  public void request(int numMessages) {
    synchronized (inbound) {
      inbound.requestMessages(numMessages);
    }
  }

  @Override
  public boolean isReady() {
    return outbound.isReady();
  }

  @Override
  public void writeMessage(InputStream message) {
    try {
      synchronized (outbound) {
        outbound.addMessage(message);
        outbound.send();
      }
    } catch (StatusException se) {
      synchronized (inbound) {
        inbound.closeAbnormal(se.getStatus());
      }
    }
  }

  @Override
  public void halfClose() {
    try {
      synchronized (outbound) {
        outbound.sendHalfClose();
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
  public void setDeadline(@Nonnull Deadline deadline) {
    synchronized (outbound) {
      outbound.setDeadline(deadline);
    }
  }

  @Override
  public Attributes getAttributes() {
    return attributes;
  }

  @Override
  public final String toString() {
    return "MultiMessageClientStream[" + inbound + "/" + outbound + "]";
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
  public void setAuthority(String authority) {
    // Ignore.
  }

  @Override
  public void setMaxInboundMessageSize(int maxSize) {
    // Ignore.
  }

  @Override
  public void setMaxOutboundMessageSize(int maxSize) {
    // Ignore.
  }

  @Override
  public void appendTimeoutInsight(InsightBuilder insight) {
    // Ignore
  }

  @Override
  public void setFullStreamDecompression(boolean fullStreamDecompression) {
    // Ignore.
  }

  @Override
  public void setDecompressorRegistry(DecompressorRegistry decompressorRegistry) {
    // Ignore.
  }

  @Override
  public void optimizeForDirectExecutor() {
    // Ignore.
  }
}
