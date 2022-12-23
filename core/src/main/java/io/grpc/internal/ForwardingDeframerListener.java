/*
 * Copyright 2019 The gRPC Authors
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

/**
 * Forwards listener callbacks to a delegate.
 */
abstract class ForwardingDeframerListener implements MessageDeframer.Listener {
  protected abstract MessageDeframer.Listener delegate();

  @Override
  public void bytesRead(int numBytes) {
    delegate().bytesRead(numBytes);
  }

  @Override
  public void messagesAvailable(StreamListener.MessageProducer producer) {
    delegate().messagesAvailable(producer);
  }

  @Override
  public void deframerClosed(boolean hasPartialMessage) {
    delegate().deframerClosed(hasPartialMessage);
  }

  @Override
  public void deframeFailed(Throwable cause) {
    delegate().deframeFailed(cause);
  }
}
