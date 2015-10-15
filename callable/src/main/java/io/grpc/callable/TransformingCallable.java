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

package io.grpc.callable;

import com.google.common.base.Preconditions;
import com.google.common.base.Throwables;

import io.grpc.Channel;
import io.grpc.ClientCall;
import io.grpc.Metadata;
import io.grpc.Status;
import io.grpc.StatusException;
import io.grpc.internal.SerializingExecutor;

import java.util.concurrent.Executor;
import java.util.concurrent.Semaphore;

import javax.annotation.Nullable;

/**
 * Helper type for the implementation of {@link Callable} methods. Please see there first for the
 * specification of what this is doing. This class is concerned with the how.
 *
 * <p>Implements the transform callable, which executes a function to produce a stream of responses
 * from a stream of requests.
 */
class TransformingCallable<RequestT, ResponseT> extends Callable<RequestT, ResponseT> {

  private final Transformer<? super RequestT, ? extends ResponseT> transformer;
  @Nullable private final Executor executor;

  TransformingCallable(Transformer<? super RequestT, ? extends ResponseT> transformer,
      Executor executor) {
    this.transformer = Preconditions.checkNotNull(transformer);
    this.executor = executor;
  }

  @Override
  public String toString() {
    return "transforming(...)";
  }

  @Override
  public ClientCall<RequestT, ResponseT> newCall(Channel channel) {
    return new TransformCall();
  }

  /**
   * Implements the transforming call. If an executor is provided, delivery of results will
   * happen asynchronously and flow control is respected. If not, results will be delivered
   * to the listener immediately on sendMessage(). This violates the ClientCall contract as
   * methods on Call are supposed to be non-blocking, whereas methods on Listener can block.
   * In most practical cases, this should not matter (see also high-level documentation in
   * Callable).
   *
   * <p>Note that this class does not need to be thread-safe since (a) the contract for
   * ClientCall does not require thread-safeness (b) we use a SerializingExecutor for
   * asynchronous callbacks which is guaranteed to run not more than one thread.
   */
  private class TransformCall extends ClientCall<RequestT, ResponseT> {

    private final SerializingExecutor callExecutor =
        executor == null ? null : new SerializingExecutor(executor);
    private final Semaphore semaphore = new Semaphore(0);
    private ClientCall.Listener<ResponseT> listener;
    private boolean sentClose;

    @Override
    public void start(ClientCall.Listener<ResponseT> listener, Metadata headers) {
      this.listener = listener;
    }

    @Override
    public void request(int numMessages) {
      if (callExecutor != null) {
        semaphore.release(numMessages);
      }
    }

    @Override
    public void cancel() {
      if (!sentClose) {
        listener.onClose(Status.CANCELLED, new Metadata());
        sentClose = true;
      }
    }

    @Override
    public void sendMessage(final RequestT message) {
      if (callExecutor == null) {
        doSend(message);
        return;
      }
      callExecutor.execute(new Runnable() {
        @Override public void run() {
          try {
            semaphore.acquire();
            doSend(message);
          } catch (Throwable t) {
            cancel();
            throw Throwables.propagate(t);
          }
        }
      });
    }

    @SuppressWarnings("deprecation") // e.getStatus()
    private void doSend(RequestT message) {
      try {
        listener.onMessage(transformer.apply(message));
      } catch (StatusException e) {
        sentClose = true;
        listener.onClose(e.getStatus(), new Metadata());
      } catch (Throwable t) {
        // TODO(wgg): should we throw anything else here, or catch like below? Catching might
        // be an issue for debugging.
        sentClose = true;
        listener.onClose(Status.fromThrowable(t), new Metadata());
      }
    }

    @Override
    public void halfClose() {
      if (callExecutor == null) {
        doClose();
        return;
      }
      callExecutor.execute(new Runnable() {
        @Override public void run() {
          doClose();
        }
      });
    }

    private void doClose() {
      if (!sentClose) {
        sentClose = true;
        listener.onClose(Status.OK, new Metadata());
      }
    }
  }
}
