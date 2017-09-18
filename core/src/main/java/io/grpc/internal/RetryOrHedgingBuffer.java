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
import static io.grpc.internal.Substream.Progress.CANCELLED;
import static io.grpc.internal.Substream.Progress.COMPLETED;

import io.grpc.ClientCall;
import io.grpc.MethodDescriptor;
import io.grpc.internal.Substream.Progress;
import java.util.ArrayList;
import java.util.List;

/**
 * A storage for buffering messages that could be used for retry or hedging.
 */
abstract class RetryOrHedgingBuffer<ReqT> {
  protected final Object lock = new Object();
  protected final MethodDescriptor<ReqT, ?> method;
  protected volatile boolean committed;
  protected int requests;
  protected boolean halfCloseCalled;
  protected Substream winningSubstream;

  RetryOrHedgingBuffer(MethodDescriptor<ReqT, ?> method) {
    this.method = method;
  }

  /**
   * Enqueues a message.
   *
   * @return true if enqueued, indicating a resume on all substreams is required; otherwise
   *     indicating that the buffer has been committed already
   */
  abstract boolean enqueueMessage(ReqT message);

  /**
   * Enqueues a {@link ClientCall#request(int)} instruction.
   *
   * @return true if enqueued, indicating a resume on all substreams is required; otherwise
   *     indicating that the buffer has been committed already
   */
  final boolean enqueueRequest(int requests) {
    if (committed) {
      return false;
    }

    synchronized (lock) {
      if (committed) {
        return false;
      }
      this.requests += requests;
      return true;
    }
  }

  /**
   * Enqueues a {@link ClientCall#halfClose()} instruction.
   */
  final boolean enqueueHalfClose() {
    if (committed) {
      return false;
    }

    synchronized (lock) {
      if (committed) {
        return false;
      }
      halfCloseCalled = true;
      return true;
    }
  }

  /**
   * Starts the specified client stream and replays all the messages and requests enqueued up to the
   * current moment.
   */
  final void replay(Substream substream) {
    if (committed) {
      substream.updateProgress(CANCELLED);
    }

    substream.start();
    resume(substream);
  }

  /**
   * Resume replay if new messages or requests have been enqueued.
   */
  abstract void resume(Substream substream);

  /**
   * The call is committed. All retrying substreams except the winner need be cancelled.
   */
  final void commit(Substream winningSubstream) {
    synchronized (lock) {
      if (committed) {
        return;
      }
      committed = true;
      this.winningSubstream = winningSubstream;
    }
  }

  /**
   * RetryOrHedgingBuffer for unary or server-streaming call.
   */
  static final class UnaryReqBuffer<ReqT> extends RetryOrHedgingBuffer<ReqT> {
    // Buffered message.
    private ReqT message;

    UnaryReqBuffer(MethodDescriptor<ReqT, ?> method) {
      super(method);
    }

    @Override
    public boolean enqueueMessage(ReqT message) {
      if (committed) {
        return false;
      }

      synchronized (lock) {
        if (committed) {
          return false;
        }
        checkState(this.message == null, "message already enqueued");
        this.message = message;
        enqueueHalfClose();
        return true;
      }
    }

    @Override
    public void resume(Substream substream) {
      checkNotNull(substream, "substream");

      synchronized (substream) {
        Progress progress = substream.progress();
        checkState(progress.messagesSent() <= 1, "messagesSent() should <= 1");

        if (progress == CANCELLED || progress == COMPLETED) {
          return;
        }

        if (winningSubstream != null) {
          if (winningSubstream != substream) {
            substream.updateProgress(CANCELLED);
          } else {
            // drain
            if (requests > progress.requests()) {
              substream.clientStream().request(requests - progress.requests());
            }
            if (progress.messagesSent() == 0 && message != null) {
              // TODO(notcarl): Find out if streamRequest needs to be closed.
              substream.clientStream().writeMessage(method.streamRequest(message));
            }
            if (halfCloseCalled && !progress.halfCloseCalled()) {
              substream.clientStream().halfClose();
            }
            // release memory
            message = null;
            substream.updateProgress(COMPLETED);
          }
          return;
        }

        boolean savedHalfCloseCalledd = halfCloseCalled;
        ReqT savedMessage = message;
        int savedRequest = requests;
        if (savedRequest > progress.requests()) {
          substream.clientStream().request(savedRequest - progress.requests());
        }

        if (progress.messagesSent() == 0 && savedMessage != null) {
          substream.clientStream().writeMessage(method.streamRequest(savedMessage));
          if (savedHalfCloseCalledd && !progress.halfCloseCalled()) {
            substream.clientStream().halfClose();
          }
          substream.updateProgress(new Progress(1, savedRequest, savedHalfCloseCalledd));
        } else if (progress.messagesSent() == 1) {
          if (savedHalfCloseCalledd && !progress.halfCloseCalled()) {
            substream.clientStream().halfClose();
          }
          substream.updateProgress(new Progress(1, savedRequest, savedHalfCloseCalledd));
        } else {
          substream.updateProgress(
              new Progress(0, savedRequest, false));
        }
      }
    }
  }


  /**
   * RetryOrHedgingBuffer for client-streaming or bidi-streaming call.
   */
  static final class StreamingReqBuffer<ReqT> extends RetryOrHedgingBuffer<ReqT> {

    // Buffered messages.
    private List<ReqT> messages = new ArrayList<ReqT>();

    StreamingReqBuffer(MethodDescriptor<ReqT, ?> method) {
      super(method);
    }

    @Override
    public boolean enqueueMessage(ReqT message) {
      if (committed) {
        return false;
      }

      synchronized (lock) {
        if (committed) {
          return false;
        }
        messages.add(message);
        return true;
      }
    }

    @Override
    public void resume(Substream substream) {
      checkNotNull(substream, "substream");

      synchronized (substream) {

        Progress progress = substream.progress();

        if (progress == CANCELLED || progress == COMPLETED) {
          return;
        }

        boolean savedHalfCloseCalled = halfCloseCalled;
        List<ReqT> savedMessages = messages;
        int savedRequests = requests;
        if (savedRequests > progress.requests()) {
          substream.clientStream().request(savedRequests - progress.requests());
        }
        int size = savedMessages.size();
        int i = progress.messagesSent();
        while (true) {
          if (winningSubstream != null) {
            if (winningSubstream != substream) {
              substream.updateProgress(CANCELLED);
            } else {
              // release buffer
              messages = null;
              // drain
              if (requests > savedRequests) {
                substream.clientStream().request(requests - savedRequests);
              }
              while (i < savedMessages.size()) {
                // TODO(notcarl): Find out if streamRequest needs to be closed.
                substream.clientStream().writeMessage(method.streamRequest(savedMessages.get(i)));
                savedMessages.set(i, null);
                i++;
              }
              if (halfCloseCalled && !progress.halfCloseCalled()) {
                substream.clientStream().halfClose();
              }
              substream.updateProgress(COMPLETED);
            }
            return;
          }

          if (i < size) {
            ReqT message = savedMessages.get(i);
            if (message != null) {
              // TODO(notcarl): Find out if streamRequest needs to be closed.
              substream.clientStream().writeMessage(method.streamRequest(message));
              i++;
            }
          } else {
            if (savedHalfCloseCalled && !progress.halfCloseCalled()) {
              substream.clientStream().halfClose();
            }
            substream.updateProgress(new Progress(size, savedRequests, savedHalfCloseCalled));
            return;
          }
        }
      }
    }
  }
}

