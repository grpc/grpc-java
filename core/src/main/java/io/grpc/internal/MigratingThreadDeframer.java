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

import static com.google.common.base.Preconditions.checkNotNull;

import io.grpc.Decompressor;
import io.perfmark.Link;
import io.perfmark.PerfMark;
import java.io.Closeable;
import java.io.InputStream;
import java.util.ArrayDeque;
import java.util.Queue;
import javax.annotation.concurrent.GuardedBy;

/**
 * A deframer that moves decoding between the transport and app threads based on which is more
 * efficient at that moment.
 */
final class MigratingThreadDeframer implements ThreadOptimizedDeframer {
  private interface Op {
    void run(boolean isDeframerOnTransportThread);
  }

  private final MessageDeframer.Listener transportListener;
  private final ApplicationThreadDeframerListener appListener;
  private final MigratingDeframerListener migratingListener;
  private final ApplicationThreadDeframerListener.TransportExecutor transportExecutor;
  private final MessageDeframer deframer;
  private final DeframeMessageProducer messageProducer = new DeframeMessageProducer();

  private final Object lock = new Object();
  /**
   * {@code true} means decoding on transport thread.
   *
   * <p>Invariant: if there are outstanding requests, then deframerOnTransportThread=true. Otherwise
   * deframerOnTransportThread=false.
   */
  @GuardedBy("lock")
  private boolean deframerOnTransportThread;
  @GuardedBy("lock")
  private final Queue<Op> opQueue = new ArrayDeque<>();
  @GuardedBy("lock")
  private boolean messageProducerEnqueued;

  public MigratingThreadDeframer(
      MessageDeframer.Listener listener,
      ApplicationThreadDeframerListener.TransportExecutor transportExecutor,
      MessageDeframer deframer) {
    this.transportListener =
        new SquelchLateMessagesAvailableDeframerListener(checkNotNull(listener, "listener"));
    this.transportExecutor = checkNotNull(transportExecutor, "transportExecutor");
    this.appListener = new ApplicationThreadDeframerListener(transportListener, transportExecutor);
    // Starts on app thread
    this.migratingListener = new MigratingDeframerListener(appListener);
    deframer.setListener(migratingListener);
    this.deframer = deframer;
  }

  @Override
  public void setMaxInboundMessageSize(int messageSize) {
    deframer.setMaxInboundMessageSize(messageSize);
  }

  @Override
  public void setDecompressor(Decompressor decompressor) {
    deframer.setDecompressor(decompressor);
  }

  @Override
  public void setFullStreamDecompressor(GzipInflatingBuffer fullStreamDecompressor) {
    deframer.setFullStreamDecompressor(fullStreamDecompressor);
  }

  private boolean runWhereAppropriate(Op op) {
    return runWhereAppropriate(op, true);
  }

  private boolean runWhereAppropriate(Op op, boolean currentThreadIsTransportThread) {
    boolean deframerOnTransportThreadCopy;
    boolean alreadyEnqueued;
    synchronized (lock) {
      deframerOnTransportThreadCopy = deframerOnTransportThread;
      alreadyEnqueued = messageProducerEnqueued;
      if (!deframerOnTransportThreadCopy) {
        opQueue.offer(op);
        messageProducerEnqueued = true;
      }
    }
    if (deframerOnTransportThreadCopy) {
      op.run(/*isDeframerOnTransportThread=*/true);
      return true;
    } else {
      if (!alreadyEnqueued) {
        if (currentThreadIsTransportThread) {
          PerfMark.startTask("MigratingThreadDeframer.messageAvailable");
          try {
            transportListener.messagesAvailable(messageProducer);
          } finally {
            PerfMark.stopTask("MigratingThreadDeframer.messageAvailable");
          }
        } else {
          final Link link = PerfMark.linkOut();
          // SLOW path. This is the "normal" thread-hopping approach for request() when _not_ using
          // MigratingThreadDeframer
          transportExecutor.runOnTransportThread(new Runnable() {
            @Override public void run() {
              PerfMark.startTask("MigratingThreadDeframer.messageAvailable");
              PerfMark.linkIn(link);
              try {
                transportListener.messagesAvailable(messageProducer);
              } finally {
                PerfMark.stopTask("MigratingThreadDeframer.messageAvailable");
              }
            }
          });
        }
      }
      return false;
    }
  }

  // May be called from an arbitrary thread
  @Override
  public void request(final int numMessages) {
    class RequestOp implements Op {
      @Override public void run(boolean isDeframerOnTransportThread) {
        if (isDeframerOnTransportThread) {
          final Link link = PerfMark.linkOut();
          // We may not be currently on the transport thread, so jump over to it and then do the
          // necessary processing
          transportExecutor.runOnTransportThread(new Runnable() {
            @Override public void run() {
              PerfMark.startTask("MigratingThreadDeframer.request");
              PerfMark.linkIn(link);
              try {
                // Since processing continues from transport thread while this runnable was
                // enqueued, the state may have changed since we ran runOnTransportThread. So we
                // must make sure deframerOnTransportThread==true
                requestFromTransportThread(numMessages);
              } finally {
                PerfMark.stopTask("MigratingThreadDeframer.request");
              }
            }
          });
          return;
        }
        PerfMark.startTask("MigratingThreadDeframer.request");
        try {
          deframer.request(numMessages);
        } catch (Throwable t) {
          appListener.deframeFailed(t);
          deframer.close(); // unrecoverable state
        } finally {
          PerfMark.stopTask("MigratingThreadDeframer.request");
        }
      }
    }

    runWhereAppropriate(new RequestOp(), false);
  }

  private void requestFromTransportThread(final int numMessages) {
    class RequestAgainOp implements Op {
      @Override public void run(boolean isDeframerOnTransportThread) {
        if (!isDeframerOnTransportThread) {
          // State changed. Go back and try again
          request(numMessages);
          return;
        }
        try {
          deframer.request(numMessages);
        } catch (Throwable t) {
          appListener.deframeFailed(t);
          deframer.close(); // unrecoverable state
        }
        if (!deframer.hasPendingDeliveries()) {
          synchronized (lock) {
            PerfMark.event("MigratingThreadDeframer.deframerOnApplicationThread");
            migratingListener.setDelegate(appListener);
            deframerOnTransportThread = false;
          }
        }
      }
    }

    runWhereAppropriate(new RequestAgainOp());
  }

  @Override
  public void deframe(final ReadableBuffer data) {
    class DeframeOp implements Op, Closeable {
      @Override public void run(boolean isDeframerOnTransportThread) {
        PerfMark.startTask("MigratingThreadDeframer.deframe");
        try {
          if (isDeframerOnTransportThread) {
            deframer.deframe(data);
            return;
          }

          try {
            deframer.deframe(data);
          } catch (Throwable t) {
            appListener.deframeFailed(t);
            deframer.close(); // unrecoverable state
          }
        } finally {
          PerfMark.stopTask("MigratingThreadDeframer.deframe");
        }
      }

      @Override public void close() {
        data.close();
      }
    }

    runWhereAppropriate(new DeframeOp());
  }

  @Override
  public void closeWhenComplete() {
    class CloseWhenCompleteOp implements Op {
      @Override public void run(boolean isDeframerOnTransportThread) {
        deframer.closeWhenComplete();
      }
    }

    runWhereAppropriate(new CloseWhenCompleteOp());
  }

  @Override
  public void close() {
    class CloseOp implements Op {
      @Override public void run(boolean isDeframerOnTransportThread) {
        deframer.close();
      }
    }

    if (!runWhereAppropriate(new CloseOp())) {
      deframer.stopDelivery();
    }
  }

  class DeframeMessageProducer implements StreamListener.MessageProducer, Closeable {
    @Override
    public InputStream next() {
      while (true) {
        InputStream is = appListener.messageReadQueuePoll();
        if (is != null) {
          return is;
        }
        Op op;
        synchronized (lock) {
          op = opQueue.poll();
          if (op == null) {
            if (deframer.hasPendingDeliveries()) {
              PerfMark.event("MigratingThreadDeframer.deframerOnTransportThread");
              migratingListener.setDelegate(transportListener);
              deframerOnTransportThread = true;
            }
            messageProducerEnqueued = false;
            return null;
          }
        }
        op.run(/*isDeframerOnTransportThread=*/false);
      }
    }

    @Override
    public void close() {
      while (true) {
        Op op;
        synchronized (lock) {
          do {
            op = opQueue.poll();
          } while (!(op == null || op instanceof Closeable));
          if (op == null) {
            messageProducerEnqueued = false;
            return;
          }
        }
        GrpcUtil.closeQuietly((Closeable) op);
      }
    }
  }

  static class MigratingDeframerListener extends ForwardingDeframerListener {
    private MessageDeframer.Listener delegate;

    public MigratingDeframerListener(MessageDeframer.Listener delegate) {
      setDelegate(delegate);
    }

    @Override
    protected MessageDeframer.Listener delegate() {
      return delegate;
    }

    public void setDelegate(MessageDeframer.Listener delegate) {
      this.delegate = checkNotNull(delegate, "delegate");
    }
  }
}
