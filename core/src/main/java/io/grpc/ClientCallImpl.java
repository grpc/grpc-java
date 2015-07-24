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

package io.grpc;

import static com.google.common.base.Preconditions.checkArgument;
import static com.google.common.base.Preconditions.checkNotNull;
import static com.google.common.base.Preconditions.checkState;
import static io.grpc.ChannelImpl.TIMEOUT_KEY;

import com.google.common.base.Preconditions;
import com.google.common.base.Throwables;

import io.grpc.Metadata.Trailers;
import io.grpc.MethodDescriptor.MethodType;
import io.grpc.transport.ClientStream;
import io.grpc.transport.ClientStreamListener;
import io.grpc.transport.ClientTransport;
import io.grpc.transport.HttpUtil;

import java.io.InputStream;
import java.util.ArrayList;
import java.util.List;
import java.util.Random;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.logging.Level;
import java.util.logging.Logger;

import javax.annotation.concurrent.GuardedBy;

/**
 * Implementation of {@link ClientCall}.
 */
final class ClientCallImpl<ReqT, RespT> extends ClientCall<ReqT, RespT> {
  private static final Logger log = Logger.getLogger(ClientCallImpl.class.getName());
  /**
   * Parameters from https://github.com/grpc/grpc/blob/master/doc/connection-backoff.md
   */
  private static final double INITIAL_BACKOFF_MILLIS = 1 * 1000;
  private static final double MULTIPLIER = 1.6;
  private static final double MAX_BACKOFF_MILLIS = 2 * 60 * 1000;
  private static final double MIN_CONNECT_TIMEOUT_MILLIS = 20 * 1000;
  private static final double JITTER = .2;

  private final Object lock = new Object();
  private final MethodDescriptor<ReqT, RespT> method;
  private final SerializingExecutor callExecutor;
  private final boolean unaryRequest;
  private final CallOptions callOptions;
  private ClientCall.Listener<?> callListener;
  @GuardedBy("lock")
  private ClientStream stream;
  private volatile ScheduledFuture<?> deadlineCancellationFuture;
  private ScheduledFuture<?> retryFuture;
  private ClientTransportProvider clientTransportProvider;
  private String userAgent;
  private ScheduledExecutorService scheduledExecutor;
  private boolean initialBackoffComplete;
  private double previousDelayMillis = INITIAL_BACKOFF_MILLIS;
  private State state = State.INIT;
  private final List<Runnable> queuedOperations = new ArrayList<Runnable>();

  ClientCallImpl(MethodDescriptor<ReqT, RespT> method, SerializingExecutor executor,
      CallOptions callOptions, ClientTransportProvider clientTransportProvider,
      ScheduledExecutorService deadlineCancellationExecutor) {
    this.method = method;
    this.callExecutor = executor;
    this.unaryRequest = method.getType() == MethodType.UNARY
        || method.getType() == MethodType.SERVER_STREAMING;
    this.callOptions = callOptions;
    this.clientTransportProvider = clientTransportProvider;
    this.scheduledExecutor = deadlineCancellationExecutor;
  }

   /**
    * Provider of {@link ClientTransport}s.
    */
  interface ClientTransportProvider {
    /**
     * @return a client transport, or null if no more transports can be created.
     */
    ClientTransport get();
  }

  private enum State {INIT, STARTED, HALF_CLOSED, CANCELLED}

  ClientCallImpl<ReqT, RespT> setUserAgent(String userAgent) {
    this.userAgent = userAgent;
    return this;
  }

  @Override
  public void start(ClientCall.Listener<RespT> observer, Metadata.Headers headers) {
    callListener = checkNotNull(observer, "No call listener provided");

    checkState(state == State.INIT, "Already Started");
    state = State.STARTED;
    Long deadlineNanoTime = callOptions.getDeadlineNanoTime();
    ClientStreamListener listener = new ClientStreamListenerImpl(observer, deadlineNanoTime);

    // Fill out timeout on the headers
    headers.removeAll(TIMEOUT_KEY);
    // Convert the deadline to timeout. Timeout is more favorable than deadline on the wire
    // because timeout tolerates the clock difference between machines.
    long timeoutMicros = 0;
    if (deadlineNanoTime != null) {
      timeoutMicros = TimeUnit.NANOSECONDS.toMicros(deadlineNanoTime - System.nanoTime());
      if (timeoutMicros <= 0) {
        closeCallPrematurely(listener, Status.DEADLINE_EXCEEDED);
        return;
      }
      headers.put(TIMEOUT_KEY, timeoutMicros);
    }

    // Fill out the User-Agent header.
    headers.removeAll(HttpUtil.USER_AGENT_KEY);
    if (userAgent != null) {
      headers.put(HttpUtil.USER_AGENT_KEY, userAgent);
    }

    boolean started = startInternal(listener, headers);
    // Start the deadline timer after stream creation because it will close the stream
    if (started && deadlineNanoTime != null) {
      deadlineCancellationFuture = startDeadlineTimer(timeoutMicros);
    }
  }

  private boolean startInternal(
      final ClientStreamListener listener, final Metadata.Headers headers) {
    ClientTransport transport;
    try {
      transport = clientTransportProvider.get();
    } catch (RuntimeException ex) {
      closeCallPrematurely(listener, Status.fromThrowable(ex));
      return false;
    }
    if (transport == null) {
      closeCallPrematurely(listener, Status.UNAVAILABLE.withDescription("Channel is shutdown"));
      return false;
    }

    synchronized (lock) {
      try {
        stream = transport.newStream(method, headers, listener);
      } catch (IllegalStateException e) {
        // We can race with the transport and end up trying to use a terminated transport.
        // TODO(ejona86): Improve the API to remove the possibility of the race.
        log.log(Level.INFO, "Attempted to use a closed transport, retrying.", e);
        retryFuture = scheduledExecutor.schedule(new Runnable() {
          @Override
          public void run() {
            try {
              startInternal(listener, headers);
            } catch (Exception e) {
              // TODO(carl-mastrangelo): Cancel the RPC ~somehow~
              log.log(Level.WARNING, "Uncaught exception attempting to start transport", e);
            }
          }
        }, (long) getNextDelayMillis(), TimeUnit.MILLISECONDS);
        return true;
      }
      // This will typically be empty, and no calls will be added to it once stream is not null.
      for (Runnable queuedCall : queuedOperations) {
        queuedCall.run();
      }
    }

    return true;
  }

  @Override
  public void request(final int numMessages) {
    checkState(state == State.STARTED || state == State.HALF_CLOSED,
        "Call was either not started or canceled");
    synchronized (lock) {
      if (stream == null) {
        queuedOperations.add(new Runnable() {
          @Override
          public void run() {
            stream.request(numMessages);
          }
        });
        return;
      }
    }
    stream.request(numMessages);
  }

  @Override
  public void cancel() {
    // It is always okay to cancel a stream, so don't bother checking state transitions.
    if (state != State.CANCELLED) {
      state = State.CANCELLED;
      synchronized (lock) {
        queuedOperations.clear();
        // Cancel is called in exception handling cases, so it may be the case that the
        // stream was never successfully created.
        if (stream != null) {
          stream.cancel(Status.CANCELLED);
        } else {
          // If start was never called, the listener might not be present.
          if (callListener != null) {
            callListener.onClose(Status.CANCELLED, new Trailers());
          }
        }
      }
    }
  }

  @Override
  public void halfClose() {
    checkState(state == State.STARTED, "Call was either not started or already closing: %s", state);
    state = State.HALF_CLOSED;
    synchronized (lock) {
      if (stream == null) {
        queuedOperations.add(new Runnable() {
          @Override
          public void run() {
            stream.halfClose();
          }
        });
        return;
      }
    }
    stream.halfClose();
  }

  @Override
  public void sendPayload(final ReqT payload) {
    checkState(state == State.STARTED, "Call was either not started or already closing: %s", state);
    Runnable operation = new Runnable() {
      @Override
      public void run() {
        boolean failed = true;
        try {
          InputStream payloadIs = method.streamRequest(payload);
          stream.writeMessage(payloadIs);
          failed = false;
        } finally {
          // TODO(notcarl): Find out if payloadIs needs to be closed.
          if (failed) {
            cancel();
          }
        }
        // For unary requests, we don't flush since we know that halfClose should be coming soon.
        // This allows us to piggy-back the END_STREAM=true on the last payload frame without
        // opening the possibility of broken applications forgetting to call halfClose without
        // noticing.
        if (!unaryRequest) {
          stream.flush();
        }
      }
    };
    synchronized (lock) {
      if (stream == null) {
        queuedOperations.add(operation);
        return;
      }
    }
    operation.run();
  }

  @Override
  public boolean isReady() {
    synchronized (lock) {
      if (stream == null) {
        return false;
      }
    }
    return stream.isReady();
  }

  /**
   * Close the call before the stream is created.
   */
  private void closeCallPrematurely(ClientStreamListener listener, Status status) {
    Preconditions.checkState(stream == null, "Stream already created");
    stream = new NoopClientStream();
    listener.closed(status, new Metadata.Trailers());
  }

  private ScheduledFuture<?> startDeadlineTimer(long timeoutMicros) {
    return scheduledExecutor.schedule(new Runnable() {
      @Override
      public void run() {
        synchronized (lock) {
          if (retryFuture != null) {
            retryFuture.cancel(true);
          }
          if (stream != null) {
            stream.cancel(Status.DEADLINE_EXCEEDED);
          } else {
            callListener.onClose(Status.DEADLINE_EXCEEDED, new Metadata.Trailers());
          }
        }
      }
    }, timeoutMicros, TimeUnit.MICROSECONDS);
  }

  private double getNextDelayMillis() {
    if (!initialBackoffComplete) {
      initialBackoffComplete = true;
      return MIN_CONNECT_TIMEOUT_MILLIS;
    }
    double currentDelayMillis = previousDelayMillis;
    previousDelayMillis =  Math.min(currentDelayMillis * MULTIPLIER, MAX_BACKOFF_MILLIS);
    return currentDelayMillis
        + uniformRandom(-JITTER * currentDelayMillis, JITTER * currentDelayMillis);
  }

  private double uniformRandom(double low, double high) {
    checkArgument(high >= low);
    double mag = high - low;
    return new Random().nextDouble() * mag + low;
  }

  private class ClientStreamListenerImpl implements ClientStreamListener {
    private final Listener<RespT> observer;
    private final Long deadlineNanoTime;
    private boolean closed;

    public ClientStreamListenerImpl(Listener<RespT> observer, Long deadlineNanoTime) {
      Preconditions.checkNotNull(observer);
      this.observer = observer;
      this.deadlineNanoTime = deadlineNanoTime;
    }

    @Override
    public void headersRead(final Metadata.Headers headers) {
      callExecutor.execute(new Runnable() {
        @Override
        public void run() {
          try {
            if (closed) {
              return;
            }

            observer.onHeaders(headers);
          } catch (Throwable t) {
            cancel();
            throw Throwables.propagate(t);
          }
        }
      });
    }

    @Override
    public void messageRead(final InputStream message) {
      callExecutor.execute(new Runnable() {
        @Override
        public void run() {
          try {
            if (closed) {
              return;
            }

            try {
              observer.onPayload(method.parseResponse(message));
            } finally {
              message.close();
            }
          } catch (Throwable t) {
            cancel();
            throw Throwables.propagate(t);
          }
        }
      });
    }

    @Override
    public void closed(Status status, Metadata.Trailers trailers) {
      if (status.getCode() == Status.Code.CANCELLED && deadlineNanoTime != null) {
        // When the server's deadline expires, it can only reset the stream with CANCEL and no
        // description. Since our timer may be delayed in firing, we double-check the deadline and
        // turn the failure into the likely more helpful DEADLINE_EXCEEDED status. This is always
        // safe, but we avoid wasting resources getting the nanoTime() when unnecessary.
        if (deadlineNanoTime <= System.nanoTime()) {
          status = Status.DEADLINE_EXCEEDED;
          // Replace trailers to prevent mixing sources of status and trailers.
          trailers = new Metadata.Trailers();
        }
      }
      final Status savedStatus = status;
      final Metadata.Trailers savedTrailers = trailers;
      callExecutor.execute(new Runnable() {
        @Override
        public void run() {
          closed = true;
          // manually optimize the volatile read
          ScheduledFuture<?> future = deadlineCancellationFuture;
          if (future != null) {
            future.cancel(false);
          }
          observer.onClose(savedStatus, savedTrailers);
        }
      });
    }

    @Override
    public void onReady() {
      callExecutor.execute(new Runnable() {
        @Override
        public void run() {
          observer.onReady();
        }
      });
    }
  }

  private static final class NoopClientStream implements ClientStream {
    @Override public void writeMessage(InputStream message) {}

    @Override public void flush() {}

    @Override public void cancel(Status reason) {}

    @Override public void halfClose() {}

    @Override public void request(int numMessages) {}

    /**
     * Always returns {@code false}, since this is only used when the startup of the {@link
     * ClientCall} fails (i.e. the {@link ClientCall} is closed).
     */
    @Override public boolean isReady() {
      return false;
    }
  }
}

