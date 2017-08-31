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
import static io.grpc.internal.Substream.Progress.CANCELLED;
import static io.grpc.internal.Substream.Progress.COMPLETED;

import io.grpc.ClientCall;
import io.grpc.Compressor;
import io.grpc.MethodDescriptor;
import io.grpc.internal.Substream.Progress;
import java.util.ArrayList;
import java.util.List;
import javax.annotation.concurrent.ThreadSafe;

/** A storage for buffering messages that could be used for retry or hedging. */
@ThreadSafe
final class RetryOrHedgingBuffer<ReqT> {
  private static final BufferEntry HALF_CLOSE_ENTRY =
      new BufferEntry() {
        @Override
        public void runWith(Substream substream) {
          substream.clientStream().halfClose();
        }
      };
  private static final BufferEntry FLUSH_ENTRY =
      new BufferEntry() {
        @Override
        public void runWith(Substream substream) {
          substream.clientStream().flush();
        }
      };
  // Lock for synchronizing enqueue actions and commit().
  private final Object lock = new Object();
  private final MethodDescriptor<ReqT, ?> method;
  private volatile boolean committed;
  private volatile Substream winningSubstream;
  private List<BufferEntry> buffer = new ArrayList<BufferEntry>();

  RetryOrHedgingBuffer(MethodDescriptor<ReqT, ?> method) {
    this.method = method;
  }

  boolean enqueueStart() {
    synchronized (lock) {
      if (committed) {
        return false;
      }
      buffer.add(
          new BufferEntry() {
            @Override
            public void runWith(Substream substream) {
              substream.start();
            }
          });
      return true;
    }
  }

  /**
   * Enqueues a message.
   *
   * @return true if enqueued, indicating a resume on all substreams is required; otherwise
   *     indicating that the buffer has been committed already
   */
  boolean enqueueMessage(final ReqT message) {
    if (committed) {
      return false;
    }

    synchronized (lock) {
      if (committed) {
        return false;
      }
      buffer.add(
          new BufferEntry() {
            @Override
            public void runWith(Substream substream) {
              // TODO(notcarl): Find out if streamRequest needs to be closed.
              substream.clientStream().writeMessage(method.streamRequest(message));
            }
          });
      return true;
    }
  }

  /**
   * Enqueues a {@link ClientCall#request(int)} instruction.
   *
   * @return true if enqueued, indicating a resume on all substreams is required; otherwise
   *     indicating that the buffer has been committed already
   */
  boolean enqueueRequest(final int requests) {
    if (committed) {
      return false;
    }

    synchronized (lock) {
      if (committed) {
        return false;
      }
      buffer.add(
          new BufferEntry() {
            @Override
            public void runWith(Substream substream) {
              substream.clientStream().request(requests);
            }
          });
      return true;
    }
  }

  /** Enqueues a {@link ClientCall#halfClose()} instruction. */
  boolean enqueueHalfClose() {
    synchronized (lock) {
      if (committed) {
        return false;
      }
      buffer.add(HALF_CLOSE_ENTRY);
      return true;
    }
  }

  boolean enqueueFlush() {
    if (committed) {
      return false;
    }

    synchronized (lock) {
      if (committed) {
        return false;
      }
      buffer.add(FLUSH_ENTRY);
      return true;
    }
  }

  boolean enqueueMessageCompression(final boolean enable) {
    synchronized (lock) {
      if (committed) {
        return false;
      }
      buffer.add(
          new BufferEntry() {
            @Override
            public void runWith(Substream substream) {
              substream.clientStream().setMessageCompression(enable);
            }
          });
      return true;
    }
  }

  boolean enqueueCompressor(final Compressor compressor) {
    synchronized (lock) {
      if (committed) {
        return false;
      }
      buffer.add(
          new BufferEntry() {
            @Override
            public void runWith(Substream substream) {
              substream.clientStream().setCompressor(compressor);
            }
          });
      return true;
    }
  }

  boolean enqueueFullStreamDecompression(final boolean fullStreamDecompression) {
    synchronized (lock) {
      if (committed) {
        return false;
      }
      buffer.add(
          new BufferEntry() {
            @Override
            public void runWith(Substream substream) {
              substream.clientStream().setFullStreamDecompression(fullStreamDecompression);
            }
          });
      return true;
    }
  }

  boolean enqueueMaxInboundMessageSize(final int maxSize) {
    synchronized (lock) {
      if (committed) {
        return false;
      }
      buffer.add(
          new BufferEntry() {
            @Override
            public void runWith(Substream substream) {
              substream.clientStream().setMaxInboundMessageSize(maxSize);
            }
          });
      return true;
    }
  }

  boolean enqueueMaxOutboundMessageSize(final int maxSize) {
    synchronized (lock) {
      if (committed) {
        return false;
      }
      buffer.add(
          new BufferEntry() {
            @Override
            public void runWith(Substream substream) {
              substream.clientStream().setMaxOutboundMessageSize(maxSize);
            }
          });
      return true;
    }
  }

  /** Resume replay if new messages or requests have been enqueued. */
  void resume(Substream substream) {
    checkNotNull(substream, "substream");

    synchronized (substream) {
      if (substream.isResuming()) {
        return;
      }
      if (substream.isPending()) {
        return;
      }
      substream.setResuming(true);
    }

    // Only one instance of resume() can execute below at a time until substream.setResuming(false)
    // is called.
    Progress progress = substream.progress();

    if (progress == CANCELLED || progress == COMPLETED) {
      return;
    }

    List<BufferEntry> savedBuffer = buffer;
    int size = savedBuffer.size();
    int i = progress.currentPosition();
    while (true) {
      if (winningSubstream != null) {
        if (winningSubstream != substream) {
          substream.updateProgress(CANCELLED);
        } else {
          // release buffer
          buffer = null;
          // drain
          while (i < savedBuffer.size()) {
            if (substream.progress() == CANCELLED) {
              return;
            }
            savedBuffer.get(i).runWith(substream);
            savedBuffer.set(i, null);
            i++;
          }
          substream.updateProgress(COMPLETED);
        }
        return;
      }

      if (i < size) {
        BufferEntry bufferEntry = savedBuffer.get(i);
        if (bufferEntry != null) {
          bufferEntry.runWith(substream);
          i++;
        }
      } else {
        synchronized (substream) {
          substream.setResuming(false);
          substream.updateProgress(new Progress(size));
          break;
        }
      }
    }

    // Just in case another concurrent instance of resume() with new enqueued stuffs quited
    // immediately. Here resume the new stuffs.
    if (i < savedBuffer.size()) {
      resume(substream);
    }
  }

  /**
   * The call is committed. All retrying/hedging substreams except the winner need be cancelled
   * immediately. The winner need drain the buffer immediately.
   */
  void commit(Substream winningSubstream) {
    synchronized (lock) {
      if (committed) {
        return;
      }
      committed = true;
      this.winningSubstream = winningSubstream;
    }
  }

  private interface BufferEntry {
    /** Replays the buffer entry with the given stream. */
    void runWith(Substream substream);
  }
}
