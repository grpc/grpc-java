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

package io.grpc.internal;

import java.io.Closeable;

/**
 * A delegating Listener that throws away notifications of messagesAvailable() after the deframer
 * has closed or failed. This can be used by deframers that "abuse" the MessageProducer to run work
 * on the app thread, to avoid breaking the normal invariant that there are no messages after
 * deframing is complete. Since the producer may not be run, it must not hold resources or
 * it should implement {@link Closeable}.
 */
final class SquelchLateMessagesAvailableDeframerListener extends ForwardingDeframerListener {
  private final MessageDeframer.Listener delegate;
  private boolean closed;

  public SquelchLateMessagesAvailableDeframerListener(MessageDeframer.Listener delegate) {
    this.delegate = delegate;
  }

  @Override
  protected MessageDeframer.Listener delegate() {
    return delegate;
  }

  @Override
  public void messagesAvailable(StreamListener.MessageProducer producer) {
    if (closed) {
      if (producer instanceof Closeable) {
        GrpcUtil.closeQuietly((Closeable) producer);
      }
      return;
    }
    super.messagesAvailable(producer);
  }

  @Override
  public void deframerClosed(boolean hasPartialMessage) {
    closed = true;
    super.deframerClosed(hasPartialMessage);
  }

  @Override
  public void deframeFailed(Throwable cause) {
    closed = true;
    super.deframeFailed(cause);
  }
}

