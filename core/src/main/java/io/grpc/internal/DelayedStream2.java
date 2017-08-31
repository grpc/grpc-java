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

import static com.google.common.base.Preconditions.checkNotNull;
import static com.google.common.base.Preconditions.checkState;
import static io.grpc.internal.RetriableStream.BUFFER_KEY;
import static io.grpc.internal.RetriableStream.SUBSTREAM_KEY;

import io.grpc.Attributes;
import io.grpc.Compressor;
import io.grpc.DecompressorRegistry;
import io.grpc.Status;
import java.io.InputStream;

/**
 * A forwarding ClientStream all public methods of which can only get called after pass-through.
 * Before pass-through, all actions are buffered in a {@link RetryOrHedgingBuffer}. {@link
 * RetryOrHedgingBuffer#resume(Substream)} will quit immediately if the underlying stream is an
 * instance of {@code DelayedStream2} and not yet pass-through.
 */
class DelayedStream2 implements ClientStream {

  private final Substream substream;
  private final RetryOrHedgingBuffer<?> buffer;
  /** Must hold {@code substream} lock when setting. */
  private ClientStream realStream;

  DelayedStream2() {
    // This is a hacky way to pass substream and buffer to DelayedStream2.
    // Try alternatives, if a better way exists.
    substream = checkNotNull(SUBSTREAM_KEY.get(), "substream");
    buffer = checkNotNull(BUFFER_KEY.get(), "buffer");
  }

  /**
   * Transfers all pending and future requests and mutations to the given stream.
   */
  final void passThrough(ClientStream realStream) {
    synchronized (substream) {
      if (this.realStream != null) {
        return;
      }
      this.realStream = checkNotNull(realStream, "realStream");
    }
    buffer.resume(substream);
  }

  /** Returns {@code true} once realStream is valid. */
  final boolean isPassThrough() {
    return realStream != null;
  }

  @Override
  public void setMaxInboundMessageSize(int maxSize) {
    realStream.setMaxInboundMessageSize(maxSize);
  }

  @Override
  public void setMaxOutboundMessageSize(int maxSize) {
    realStream.setMaxOutboundMessageSize(maxSize);
  }

  @Override
  public void setAuthority(String authority) {
    checkState(isPassThrough(), "The delayed stream should pass through");
    realStream.setAuthority(authority);
  }

  @Override
  public void setFullStreamDecompression(boolean fullStreamDecompression) {
    realStream.setFullStreamDecompression(fullStreamDecompression);
  }

  @Override
  public void start(ClientStreamListener listener) {
    checkState(isPassThrough(), "The delayed stream should pass through");
    realStream.start(listener);
  }

  @Override
  public Attributes getAttributes() {
    return realStream.getAttributes();
  }

  @Override
  public void writeMessage(InputStream message) {
    realStream.writeMessage(message);
  }

  @Override
  public void flush() {
    realStream.flush();
  }

  @Override
  public void cancel(Status reason) {
    realStream.cancel(reason);
  }

  @Override
  public void halfClose() {
    realStream.halfClose();
  }

  @Override
  public void request(int numMessages) {
    realStream.request(numMessages);
  }

  @Override
  public void setCompressor(Compressor compressor) {
    realStream.setCompressor(compressor);
  }

  @Override
  public void setDecompressorRegistry(DecompressorRegistry decompressorRegistry) {
    checkState(isPassThrough(), "The delayed stream should pass through");
    realStream.setDecompressorRegistry(decompressorRegistry);
  }

  @Override
  public boolean isReady() {
    return realStream.isReady();
  }

  @Override
  public void setMessageCompression(boolean enable) {
    realStream.setMessageCompression(enable);
  }
}
