/*
 * Copyright 2015 The gRPC Authors
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

import com.google.common.annotations.VisibleForTesting;
import io.grpc.Attributes;
import io.grpc.Compressor;
import io.grpc.Deadline;
import io.grpc.DecompressorRegistry;
import io.grpc.Metadata;
import io.grpc.Status;
import io.grpc.internal.ClientStreamListener.RpcProgress;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.List;
import javax.annotation.CheckReturnValue;
import javax.annotation.concurrent.GuardedBy;

/**
 * A stream that queues requests before the transport is available, and delegates to a real stream
 * implementation when the transport is available.
 *
 * <p>{@code ClientStream} itself doesn't require thread-safety. However, the state of {@code
 * DelayedStream} may be internally altered by different threads, thus internal synchronization is
 * necessary.
 */
class DelayedStream implements ClientStream {
  /** {@code true} once realStream is valid and all pending calls have been drained. */
  private volatile boolean passThrough;
  /**
   * Non-{@code null} iff start has been called. Used to assert methods are called in appropriate
   * order, but also used if an error occurs before {@code realStream} is set.
   */
  private ClientStreamListener listener;
  /** Must hold {@code this} lock when setting. */
  private ClientStream realStream;
  @GuardedBy("this")
  private Status error;
  @GuardedBy("this")
  private List<Runnable> pendingCalls = new ArrayList<>();
  @GuardedBy("this")
  private DelayedStreamListener delayedListener;
  @GuardedBy("this")
  private long startTimeNanos;
  @GuardedBy("this")
  private long streamSetTimeNanos;
  // No need to synchronize; start() synchronization provides a happens-before
  private List<Runnable> preStartPendingCalls = new ArrayList<>();

  @Override
  public void setMaxInboundMessageSize(final int maxSize) {
    checkState(listener == null, "May only be called before start");
    preStartPendingCalls.add(new Runnable() {
      @Override
      public void run() {
        realStream.setMaxInboundMessageSize(maxSize);
      }
    });
  }

  @Override
  public void setMaxOutboundMessageSize(final int maxSize) {
    checkState(listener == null, "May only be called before start");
    preStartPendingCalls.add(new Runnable() {
      @Override
      public void run() {
        realStream.setMaxOutboundMessageSize(maxSize);
      }
    });
  }

  @Override
  public void setDeadline(final Deadline deadline) {
    checkState(listener == null, "May only be called before start");
    preStartPendingCalls.add(new Runnable() {
      @Override
      public void run() {
        realStream.setDeadline(deadline);
      }
    });
  }

  @Override
  public void appendTimeoutInsight(InsightBuilder insight) {
    synchronized (this) {
      if (listener == null) {
        return;
      }
      if (realStream != null) {
        insight.appendKeyValue("buffered_nanos", streamSetTimeNanos - startTimeNanos);
        realStream.appendTimeoutInsight(insight);
      } else {
        insight.appendKeyValue("buffered_nanos", System.nanoTime() - startTimeNanos);
        insight.append("waiting_for_connection");
      }
    }
  }

  /**
   * Transfers all pending and future requests and mutations to the given stream. Method will return
   * quickly, but if the returned Runnable is non-null it must be called to complete the process.
   * The Runnable may take a while to execute.
   *
   * <p>No-op if either this method or {@link #cancel} have already been called.
   */
  // When this method returns, start() has been called on realStream or passThrough is guaranteed to
  // be true
  @CheckReturnValue
  final Runnable setStream(ClientStream stream) {
    ClientStreamListener savedListener;
    synchronized (this) {
      // If realStream != null, then either setStream() or cancel() has been called.
      if (realStream != null) {
        return null;
      }
      setRealStream(checkNotNull(stream, "stream"));
      savedListener = listener;
      if (savedListener == null) {
        assert pendingCalls.isEmpty();
        pendingCalls = null;
        passThrough = true;
      }
    }
    if (savedListener == null) {
      return null;
    } else {
      internalStart(savedListener);
      return new Runnable() {
        @Override
        public void run() {
          drainPendingCalls();
        }
      };
    }
  }

  /**
   * Called to transition {@code passThrough} to {@code true}. This method is not safe to be called
   * multiple times; the caller must ensure it will only be called once, ever. {@code this} lock
   * should not be held when calling this method.
   */
  private void drainPendingCalls() {
    assert realStream != null;
    assert !passThrough;
    List<Runnable> toRun = new ArrayList<>();
    DelayedStreamListener delayedListener = null;
    while (true) {
      synchronized (this) {
        if (pendingCalls.isEmpty()) {
          pendingCalls = null;
          passThrough = true;
          delayedListener = this.delayedListener;
          break;
        }
        // Since there were pendingCalls, we need to process them. To maintain ordering we can't set
        // passThrough=true until we run all pendingCalls, but new Runnables may be added after we
        // drop the lock. So we will have to re-check pendingCalls.
        List<Runnable> tmp = toRun;
        toRun = pendingCalls;
        pendingCalls = tmp;
      }
      for (Runnable runnable : toRun) {
        // Must not call transport while lock is held to prevent deadlocks.
        // TODO(ejona): exception handling
        runnable.run();
      }
      toRun.clear();
    }
    if (delayedListener != null) {
      delayedListener.drainPendingCallbacks();
    }
  }

  /**
   * Enqueue the runnable or execute it now. Call sites that may be called many times may want avoid
   * this method if {@code passThrough == true}.
   *
   * <p>Note that this method is no more thread-safe than {@code runnable}. It is thread-safe if and
   * only if {@code runnable} is thread-safe.
   */
  private void delayOrExecute(Runnable runnable) {
    checkState(listener != null, "May only be called after start");
    synchronized (this) {
      if (!passThrough) {
        pendingCalls.add(runnable);
        return;
      }
    }
    runnable.run();
  }

  @Override
  public void setAuthority(final String authority) {
    checkNotNull(authority, "authority");
    preStartPendingCalls.add(new Runnable() {
      @Override
      public void run() {
        realStream.setAuthority(authority);
      }
    });
  }

  @Override
  public void start(ClientStreamListener listener) {
    checkNotNull(listener, "listener");
    checkState(this.listener == null, "already started");

    Status savedError;
    boolean savedPassThrough;
    synchronized (this) {
      // If error != null, then cancel() has been called and was unable to close the listener
      savedError = error;
      savedPassThrough = passThrough;
      if (!savedPassThrough) {
        listener = delayedListener = new DelayedStreamListener(listener);
      }
      this.listener = listener;
      startTimeNanos = System.nanoTime();
    }
    if (savedError != null) {
      listener.closed(savedError, RpcProgress.PROCESSED, new Metadata());
      return;
    }

    if (savedPassThrough) {
      internalStart(listener);
    } // else internalStart() will be called by setStream
  }

  /**
   * Starts stream without synchronization. {@code listener} should be same instance as {@link
   * #listener}.
   */
  private void internalStart(ClientStreamListener listener) {
    for (Runnable runnable : preStartPendingCalls) {
      runnable.run();
    }
    preStartPendingCalls = null;
    realStream.start(listener);
  }

  @Override
  public Attributes getAttributes() {
    ClientStream savedRealStream;
    synchronized (this) {
      savedRealStream = realStream;
    }
    if (savedRealStream != null) {
      return savedRealStream.getAttributes();
    } else {
      return Attributes.EMPTY;
    }
  }

  @Override
  public void writeMessage(final InputStream message) {
    checkState(listener != null, "May only be called after start");
    checkNotNull(message, "message");
    if (passThrough) {
      realStream.writeMessage(message);
    } else {
      delayOrExecute(new Runnable() {
        @Override
        public void run() {
          realStream.writeMessage(message);
        }
      });
    }
  }

  @Override
  public void flush() {
    checkState(listener != null, "May only be called after start");
    if (passThrough) {
      realStream.flush();
    } else {
      delayOrExecute(new Runnable() {
        @Override
        public void run() {
          realStream.flush();
        }
      });
    }
  }

  // When this method returns, passThrough is guaranteed to be true
  @Override
  public void cancel(final Status reason) {
    checkState(listener != null, "May only be called after start");
    checkNotNull(reason, "reason");
    boolean delegateToRealStream = true;
    synchronized (this) {
      // If realStream != null, then either setStream() or cancel() has been called
      if (realStream == null) {
        setRealStream(NoopClientStream.INSTANCE);
        delegateToRealStream = false;
        error = reason;
      }
    }
    if (delegateToRealStream) {
      delayOrExecute(new Runnable() {
        @Override
        public void run() {
          realStream.cancel(reason);
        }
      });
    } else {
      drainPendingCalls();
      onEarlyCancellation(reason);
      // Note that listener is a DelayedStreamListener
      listener.closed(reason, RpcProgress.PROCESSED, new Metadata());
    }
  }

  protected void onEarlyCancellation(Status reason) {
  }

  @GuardedBy("this")
  private void setRealStream(ClientStream realStream) {
    checkState(this.realStream == null, "realStream already set to %s", this.realStream);
    this.realStream = realStream;
    streamSetTimeNanos = System.nanoTime();
  }

  @Override
  public void halfClose() {
    checkState(listener != null, "May only be called after start");
    delayOrExecute(new Runnable() {
      @Override
      public void run() {
        realStream.halfClose();
      }
    });
  }

  @Override
  public void request(final int numMessages) {
    checkState(listener != null, "May only be called after start");
    if (passThrough) {
      realStream.request(numMessages);
    } else {
      delayOrExecute(new Runnable() {
        @Override
        public void run() {
          realStream.request(numMessages);
        }
      });
    }
  }

  @Override
  public void optimizeForDirectExecutor() {
    checkState(listener == null, "May only be called before start");
    preStartPendingCalls.add(new Runnable() {
      @Override
      public void run() {
        realStream.optimizeForDirectExecutor();
      }
    });
  }

  @Override
  public void setCompressor(final Compressor compressor) {
    checkState(listener == null, "May only be called before start");
    checkNotNull(compressor, "compressor");
    preStartPendingCalls.add(new Runnable() {
      @Override
      public void run() {
        realStream.setCompressor(compressor);
      }
    });
  }

  @Override
  public void setFullStreamDecompression(final boolean fullStreamDecompression) {
    checkState(listener == null, "May only be called before start");
    preStartPendingCalls.add(
        new Runnable() {
          @Override
          public void run() {
            realStream.setFullStreamDecompression(fullStreamDecompression);
          }
        });
  }

  @Override
  public void setDecompressorRegistry(final DecompressorRegistry decompressorRegistry) {
    checkState(listener == null, "May only be called before start");
    checkNotNull(decompressorRegistry, "decompressorRegistry");
    preStartPendingCalls.add(new Runnable() {
      @Override
      public void run() {
        realStream.setDecompressorRegistry(decompressorRegistry);
      }
    });
  }

  @Override
  public boolean isReady() {
    if (passThrough) {
      return realStream.isReady();
    } else {
      return false;
    }
  }

  @Override
  public void setMessageCompression(final boolean enable) {
    checkState(listener != null, "May only be called after start");
    if (passThrough) {
      realStream.setMessageCompression(enable);
    } else {
      delayOrExecute(new Runnable() {
        @Override
        public void run() {
          realStream.setMessageCompression(enable);
        }
      });
    }
  }

  @VisibleForTesting
  ClientStream getRealStream() {
    return realStream;
  }

  private static class DelayedStreamListener implements ClientStreamListener {
    private final ClientStreamListener realListener;
    private volatile boolean passThrough;
    @GuardedBy("this")
    private List<Runnable> pendingCallbacks = new ArrayList<>();

    public DelayedStreamListener(ClientStreamListener listener) {
      this.realListener = listener;
    }

    private void delayOrExecute(Runnable runnable) {
      synchronized (this) {
        if (!passThrough) {
          pendingCallbacks.add(runnable);
          return;
        }
      }
      runnable.run();
    }

    @Override
    public void messagesAvailable(final MessageProducer producer) {
      if (passThrough) {
        realListener.messagesAvailable(producer);
      } else {
        delayOrExecute(new Runnable() {
          @Override
          public void run() {
            realListener.messagesAvailable(producer);
          }
        });
      }
    }

    @Override
    public void onReady() {
      if (passThrough) {
        realListener.onReady();
      } else {
        delayOrExecute(new Runnable() {
          @Override
          public void run() {
            realListener.onReady();
          }
        });
      }
    }

    @Override
    public void headersRead(final Metadata headers) {
      delayOrExecute(new Runnable() {
        @Override
        public void run() {
          realListener.headersRead(headers);
        }
      });
    }

    @Override
    public void closed(
        final Status status, final RpcProgress rpcProgress,
        final Metadata trailers) {
      delayOrExecute(new Runnable() {
        @Override
        public void run() {
          realListener.closed(status, rpcProgress, trailers);
        }
      });
    }

    public void drainPendingCallbacks() {
      assert !passThrough;
      List<Runnable> toRun = new ArrayList<>();
      while (true) {
        synchronized (this) {
          if (pendingCallbacks.isEmpty()) {
            pendingCallbacks = null;
            passThrough = true;
            break;
          }
          // Since there were pendingCallbacks, we need to process them. To maintain ordering we
          // can't set passThrough=true until we run all pendingCallbacks, but new Runnables may be
          // added after we drop the lock. So we will have to re-check pendingCallbacks.
          List<Runnable> tmp = toRun;
          toRun = pendingCallbacks;
          pendingCallbacks = tmp;
        }
        for (Runnable runnable : toRun) {
          // Avoid calling listener while lock is held to prevent deadlocks.
          // TODO(ejona): exception handling
          runnable.run();
        }
        toRun.clear();
      }
    }
  }
}
