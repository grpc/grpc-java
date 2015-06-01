/*
 * Copyright 2014, Google Inc. All rights reserved.
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

package io.grpc.transport;

import java.io.InputStream;

/**
 * An observer of {@link Stream} events. It is guaranteed to only have one concurrent callback at a
 * time.
 */
public interface StreamListener {
  /**
   * Called when more messages have become available from the remote end-point.
   *
   * <p>The provided {@code message} {@link InputStream} must be closed by the listener.
   *
   * <p>This method should return quickly, as the same thread may be used to process other streams.
   *
   * @param producer which can provide a sequence of {@link java.io.InputStream} messages to a
   *                 consumer.
   */
  void messagesAvailable(MessageProducer producer);

  /**
   * This indicates that the transport is now capable of sending additional messages
   * without requiring excessive buffering internally. This event is
   * just a suggestion and the application is free to ignore it, however doing so may
   * result in excessive buffering within the transport.
   */
  void onReady();


  /**
   * A producer of messages to a {@link MessageConsumer}.
   */
  public static interface MessageProducer {
    /**
     * Produce all available messages to the consumer.
     * @param consumer which receives all available messages.
     * @return the number of messages delivered to the consumer.
     */
    public int drainTo(MessageConsumer consumer);
  }

  /**
   * Receives messages from a {@link MessageProducer}.
   */
  public static interface MessageConsumer {

    /**
     * Receive a {@link InputStream} message.
     * @param message provided by the producer.
     * @throws Exception if message cannot be processed for any reason.
     */
    void accept(InputStream message) throws Exception;
  }
}
