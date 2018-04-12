package io.grpc.stub;

import com.google.errorprone.annotations.DoNotMock;
import io.grpc.CallOptions;
import io.grpc.Channel;
import io.grpc.ClientCall;
import io.grpc.Metadata;
import io.grpc.MethodDescriptor;
import io.grpc.Status;
import io.grpc.StatusRuntimeException;
import java.util.ArrayDeque;
import java.util.NoSuchElementException;
import java.util.Queue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.locks.Condition;
import java.util.concurrent.locks.Lock;
import java.util.concurrent.locks.ReentrantLock;
import javax.annotation.CheckReturnValue;
import javax.annotation.Nullable;

@CheckReturnValue
@DoNotMock("or else!")
public abstract class SyncCall<ReqT, RespT> {

  SyncCall() {}

  /**
   * Tries to get a response from the remote endpoint.
   *
   * @return A response or {@code null} if not available.
   * @throws StatusRuntimeException if the RPC has completed with a non-OK {@link Status}.
   */
  @Nullable
  public abstract RespT poll();

  /**
   * Tries to get a response from the remote endpoint, waiting up until the given timeout.
   *
   * @param timeout how long to wait before returning, in units of {@code unit}
   * @param unit a {@link TimeUnit} determining how to interpret {@code timeout}.
   * @return A response or {@code null} if not available.  Always returns {@code null} after the
   *         remote endpoint has half-closed.
   * @throws InterruptedException if interrupted while waiting.
   * @throws StatusRuntimeException if the RPC has completed with a non-OK {@link Status}.
   */
  @Nullable
  public abstract RespT poll(long timeout, TimeUnit unit) throws InterruptedException;

  /**
   * Tries to get a response from the remote endpoint, waiting up until the given timeout.
   * Unlike {@link #poll(long, TimeUnit)}, if the calling thread is interrupted, it will be
   * ignored.
   *
   * @param timeout how long to wait before returning, in units of {@code unit}
   * @param unit a {@link TimeUnit} determining how to interpret {@code timeout}.
   * @return A response or {@code null} if not available.  Always returns {@code null} after the
   *         remote endpoint has half-closed.
   * @throws StatusRuntimeException if the RPC has completed with a non-OK {@link Status}.
   */
  @Nullable
  public abstract RespT pollUninterruptibly(long timeout, TimeUnit unit);

  /**
   * Returns a response from the remote endpoint, waiting until one is available.
   *
   * @throws NoSuchElementException if the remote endpoint has half-closed.
   * @throws StatusRuntimeException if the RPC has completed with a non-OK {@link Status}.
   * @throws InterruptedException if interrupted while waiting
   */
  public abstract RespT take() throws InterruptedException;

  /**
   * Returns a response from the remote endpoint, waiting until one is available.  Unlike
   * {@link #take()}, if the calling thread is interrupted, it will be ignored.
   *
   * @throws NoSuchElementException if the remote endpoint has half-closed.
   * @throws StatusRuntimeException if the RPC has completed with a non-OK {@link Status}.
   */
  public abstract RespT takeUninterruptibly();

  /**
   * Tries to get a response from the remote endpoint, until the stub is writable.  If there are
   * messages available, this method will return a message even if the stub is writable.
   *
   * @return A response or {@code null} if not available.  Always returns {@code null} after the
   *         remote endpoint has half-closed.
   * @throws InterruptedException if interrupted while waiting.
   * @throws StatusRuntimeException if the RPC has completed with a non-OK {@link Status}.
   */
  @Nullable
  public abstract RespT pollUntilWritable() throws InterruptedException;

  /**
   * Tries to get a response from the remote endpoint, waiting up until the given timeout, or
   * until the stub is writable.  If there are messages available, this method will return a
   * message even if the stub is writable.
   *
   * @param timeout how long to wait before returning, in units of {@code unit}
   * @param unit a {@link TimeUnit} determining how to interpret {@code timeout}.
   * @return A response or {@code null} if not available.  Always returns {@code null} after the
   *         remote endpoint has half-closed.
   * @throws InterruptedException if interrupted while waiting.
   * @throws StatusRuntimeException if the RPC has completed with a non-OK {@link Status}.
   */
  @Nullable
  public abstract RespT pollUntilWritable(long timeout, TimeUnit unit) throws InterruptedException;

  /**
   * Tries to get a response from the remote endpoint, until the stub is writable.  If there are
   * messages available, this method will return a message even if the stub is writable.  Unlike
   * {@link #pollUntilWritable()}, if the calling thread is interrupted, it will be ignored.
   *
   * @return A response or {@code null} if not available.  Always returns {@code null} after the
   *         remote endpoint has half-closed.
   * @throws StatusRuntimeException if the RPC has completed with a non-OK {@link Status}.
   */
  @Nullable
  public abstract RespT pollUninterruptiblyUntilWritable();

  /**
   * Tries to get a response from the remote endpoint, waiting up until the given timeout, or
   * until the stub is writable.  If there are messages available, this method will return a
   * message even if the stub is writable.  Unlike {@link #pollUntilWritable(long, TimeUnit)}, if
   * the calling thread is interrupted, it will be ignored.
   *
   * @param timeout how long to wait before returning, in units of {@code unit}
   * @param unit a {@link TimeUnit} determining how to interpret {@code timeout}.
   * @return A response or {@code null} if not available.  Always returns {@code null} after the
   *         remote endpoint has half-closed.
   * @throws StatusRuntimeException if the RPC has completed with a non-OK {@link Status}.
   */
  @Nullable
  public abstract RespT pollUninterruptiblyUntilWritable(long timeout, TimeUnit unit);

  /**
   * Attempts to send a request message if it would not result in excessive buffering.
   *
   * @param req the message to send
   * @return {@code true} if the message was enqueued.
   * @throws StatusRuntimeException if the RPC has completed with a non-OK {@link Status}.
   */
  public abstract boolean offer(ReqT req);

  /**
   * Attempts to send a request message if it would not result in excessive buffering.  Waits up
   * to the given timeout.
   *
   * @param req the message to send
   * @param timeout how long to wait before returning, in units of {@code unit}
   * @param unit a {@link TimeUnit} determining how to interpret {@code timeout}.
   * @return {@code true} if the message was enqueued.
   * @throws InterruptedException if interrupted while waiting.
   * @throws StatusRuntimeException if the RPC has completed with a non-OK {@link Status}.
   */
  public abstract boolean offer(ReqT req, long timeout, TimeUnit unit) throws InterruptedException;

  /**
   * Attempts to send a request message if it would not result in excessive buffering.  Waits up
   * to the given timeout.  Unlike {@link #offer(Object, long, TimeUnit)}, if the calling thread
   * is interrupted, it will be ignored.
   *
   * @param req the message to send
   * @param timeout how long to wait before returning, in units of {@code unit}
   * @param unit a {@link TimeUnit} determining how to interpret {@code timeout}.
   * @return {@code true} if the message was enqueued.
   * @throws StatusRuntimeException if the RPC has completed with a non-OK {@link Status}.
   */
  public abstract boolean offerUninterruptibly(ReqT req, long timeout, TimeUnit unit);

  /**
   * Attempts to send a request message, blocking until there would not be excessive buffering.
   *
   * @param req the message to send
   * @throws InterruptedException if interrupted while waiting.
   * @throws StatusRuntimeException if the RPC has completed with a non-OK {@link Status}.
   */
  public abstract void put(ReqT req) throws InterruptedException;

  /**
   * Attempts to send a request message, blocking until there would not be excessive buffering.
   * Unlike {@link #put(Object)} if the calling thread is interrupted, it will be ignored.
   *
   * @param req the message to send
   * @throws StatusRuntimeException if the RPC has completed with a non-OK {@link Status}.
   */
  public abstract void putUninterruptibly(ReqT req);

  /**
   * Attempts to send a request message, buffering the message if it cannot be sent immediately.
   * Users should avoid using this method.
   *
   * @param req the message to send
   * @throws StatusRuntimeException if the RPC has completed with a non-OK {@link Status}.
   */
  public abstract void putQueued(ReqT req);

  /**
   * Attempts to send a request message, blocking until there would not be excessive buffering.
   * Waits up to the given timeout or if the stub becomes readable.   If the message can be sent
   * and the stub is readable, the message will always be sent.
   *
   * @param req the message to send
   * @param timeout how long to wait before returning, in units of {@code unit}
   * @param unit a {@link TimeUnit} determining how to interpret {@code timeout}.
   * @return {@code true} if the message was enqueued.
   * @throws InterruptedException if interrupted while waiting.
   * @throws StatusRuntimeException if the RPC has completed with a non-OK {@link Status}.
   */
  public abstract boolean offerUntilReadable(ReqT req, long timeout, TimeUnit unit)
      throws InterruptedException;

  /**
   * Attempts to send a request message, blocking until there would not be excessive buffering,
   * or until the stub becomes readable.   If the message can be sent and the stub is readable,
   * the message will always be sent.
   *
   * @param req the message to send
   * @param timeout how long to wait before returning, in units of {@code unit}
   * @param unit a {@link TimeUnit} determining how to interpret {@code timeout}.
   * @return {@code true} if the message was enqueued.
   * @throws InterruptedException if interrupted while waiting.
   * @throws StatusRuntimeException if the RPC has completed with a non-OK {@link Status}.
   */
  public abstract boolean offerUntilReadable(ReqT req) throws InterruptedException;

  /**
   * Attempts to send a request message, blocking until there would not be excessive buffering,
   * or until the stub becomes readable.   If the message can be sent and the stub is readable,
   * the message will always be sent.  Unlike {@link #offerUntilReadable(Object, long, TimeUnit)},
   * if the calling thread is interrupted, it will be ignored.
   *
   * @param req the message to send
   * @return {@code true} if the message was enqueued.
   * @throws StatusRuntimeException if the RPC has completed with a non-OK {@link Status}.
   */
  public abstract boolean offerUninterruptiblyUntilReadable(ReqT req);

  /**
   * Attempts to send a request message, blocking until there would not be excessive buffering.
   * Waits up to the given timeout or if the stub becomes readable.   If the message can be sent
   * and the stub is readable, the message will always be sent.  Unlike
   * {@link #offerUntilReadable(Object, long, TimeUnit)}, if the calling thread is interrupted,
   * it will be ignored.
   *
   * @param req the message to send
   * @param timeout how long to wait before returning, in units of {@code unit}
   * @param unit a {@link TimeUnit} determining how to interpret {@code timeout}.
   * @return {@code true} if the message was enqueued.
   * @throws StatusRuntimeException if the RPC has completed with a non-OK {@link Status}.
   */
  public abstract boolean offerUninterruptiblyUntilReadable(ReqT req, long timeout, TimeUnit unit);



  public abstract boolean isComplete();


  public static <ReqT, RespT> SyncClientCall<ReqT, RespT> call(
      Channel channel, MethodDescriptor<ReqT, RespT> method, CallOptions opts) {
    ClientCall<ReqT, RespT> c = channel.newCall(method, opts);
    return SyncClientCall.createAndStart(c, new Metadata());
  }


  public static final class SyncClientCall<ReqT, RespT> extends SyncCall<ReqT, RespT> {

    private final ClientCall<ReqT, RespT> call;
    private final Queue<RespT> responses = new ArrayDeque<RespT>();
    private Status status;
    private Metadata trailers;

    private final Lock lock = new ReentrantLock();
    private final Condition cond = lock.newCondition();

    private SyncClientCall(ClientCall<ReqT, RespT> call) {
      this.call = call;
    }

    static <ReqT, RespT> SyncClientCall<ReqT, RespT> createAndStart(
        ClientCall<ReqT, RespT> call, Metadata md) {
      SyncClientCall<ReqT, RespT> scc = new SyncClientCall<ReqT, RespT>(call);
      scc.call.start(scc.new Listener(), md);
      scc.call.request(1);
      return scc;
    }

    private final class Listener extends ClientCall.Listener<RespT> {

      @Override
      public void onReady() {
        lock.lock();
        try {
          cond.notifyAll();
        } finally {
          lock.unlock();
        }
      }

      @Override
      public void onMessage(RespT message) {
        lock.lock();
        try {
          responses.add(message);
          cond.notifyAll();
        } finally {
          lock.unlock();
        }
      }

      @Override
      public void onClose(Status status, Metadata trailers) {
        lock.lock();
        try {
          SyncClientCall.this.status = status;
          SyncClientCall.this.trailers = trailers;
          cond.notifyAll();
        } finally {
          lock.unlock();
        }
      }
    }

    @Nullable
    @Override
    public RespT poll() {
      boolean interruptible = false;
      boolean checkRead = false;
      boolean checkWrite = false;
      try {
        return poll(interruptible, checkRead, checkWrite);
      } catch (InterruptedException e) {
        throw new AssertionError(e);
      }
    }

    private void checkStatus() {
      if (status != null && !status.isOk()) {
        throw status.asRuntimeException(trailers);
      }
    }

    @Nullable
    @Override
    public RespT poll(long timeout, TimeUnit unit) throws InterruptedException {
      boolean interruptible = true;
      boolean checkRead = true;
      boolean checkWrite = false;
      return poll(timeout, unit, interruptible, checkRead, checkWrite);
    }

    private final RespT poll(
        long timeout,
        TimeUnit unit,
        boolean interruptible,
        boolean checkRead,
        boolean checkWrite) throws InterruptedException {
      long remainingNanos = unit.toNanos(timeout);
      long end = System.nanoTime() + remainingNanos;
      RespT response;
      boolean interrupted = false;
      lock.lock();
      try {
        while (true) {
          if (!checkRead && !checkWrite) {
            break;
          } else if (checkRead && (!responses.isEmpty() || status != null)) {
            break;
          } else if (checkWrite && call.isReady()) {
            break;
          }
          try {
            if (!cond.await(remainingNanos, TimeUnit.NANOSECONDS)) {
              break;
            }
          } catch (InterruptedException e) {
            if (interruptible) {
              throw e;
            }
            interrupted = true;
          }
          remainingNanos = end - System.nanoTime();
        }
        if ((response = responses.poll()) == null) {
          checkStatus();
        } else {
          call.request(1);
        }
        return response;
      } finally {
        lock.unlock();
        if (interrupted) {
          Thread.currentThread().interrupt();
        }
      }
    }

    private RespT poll(
        boolean interruptible, boolean checkRead, boolean checkWrite) throws InterruptedException {
      RespT response;
      lock.lock();
      try {
        while (true) {
          if (!checkRead && !checkWrite) {
            break;
          } else if (checkRead && (!responses.isEmpty() || status != null)) {
            break;
          } else if (checkWrite && call.isReady()) {
            break;
          }
          if (interruptible) {
            cond.await();
          } else {
            cond.awaitUninterruptibly();
          }
        }
        if ((response = responses.poll()) == null) {
          checkStatus();
        } else {
          call.request(1);
        }
        return response;
      } finally {
        lock.unlock();
      }
    }

    @Nullable
    @Override
    public RespT pollUninterruptibly(long timeout, TimeUnit unit) {
      boolean interruptible = false;
      boolean checkRead = true;
      boolean checkWrite = false;
      try {
        return poll(timeout, unit, interruptible, checkRead, checkWrite);
      } catch (InterruptedException e) {
        throw new AssertionError(e);
      }
    }

    @Override
    public RespT take() throws InterruptedException {
      boolean interruptible = true;
      boolean checkRead = true;
      boolean checkWrite = false;
      RespT response = poll(interruptible, checkRead, checkWrite);
      if (response == null) {
        assert status != null && status.isOk();
        throw new NoSuchElementException("call half closed");
      }
      return response;
    }

    @Override
    public RespT takeUninterruptibly() {
      boolean interruptible = false;
      boolean checkRead = true;
      boolean checkWrite = false;
      RespT response;
      try {
        response = poll(interruptible, checkRead, checkWrite);
      } catch (InterruptedException e) {
        throw new AssertionError(e);
      }
      if (response == null) {
        assert status != null && status.isOk();
        throw new NoSuchElementException("call half closed");
      }
      return response;
    }

    @Override
    public RespT pollUntilWritable() throws InterruptedException {
      boolean interruptible = true;
      boolean checkRead = true;
      boolean checkWrite = true;
      return poll(interruptible, checkRead, checkWrite);
    }

    @Nullable
    @Override
    public RespT pollUntilWritable(long timeout, TimeUnit unit) throws InterruptedException {
      boolean interruptible = true;
      boolean checkRead = true;
      boolean checkWrite = true;
      return poll(timeout, unit, interruptible, checkRead, checkWrite);
    }

    @Override
    @Nullable
    public RespT pollUninterruptiblyUntilWritable() {
      boolean interruptible = false;
      boolean checkRead = true;
      boolean checkWrite = true;
      try {
        return poll(interruptible, checkRead, checkWrite);
      } catch (InterruptedException e) {
        throw new AssertionError(e);
      }
    }

    @Nullable
    @Override
    public RespT pollUninterruptiblyUntilWritable(long timeout, TimeUnit unit) {
      boolean interruptible = false;
      boolean checkRead = true;
      boolean checkWrite = true;
      try {
        return poll(timeout, unit, interruptible, checkRead, checkWrite);
      } catch (InterruptedException e) {
        throw new AssertionError(e);
      }
    }

    @Override
    public boolean offer(ReqT req) {
      lock.lock();
      try {
        checkStatus();
        if (call.isReady() && status == null) {
          call.sendMessage(req);
          return true;
        }
        return false;
      } finally {
        lock.unlock();
      }
    }

    @Override
    public boolean offer(ReqT req, long timeout, TimeUnit unit) throws InterruptedException {
      long remainingNanos = unit.toNanos(timeout);
      long end = System.nanoTime() + remainingNanos;
      boolean isReady;
      lock.lock();
      try {
        checkStatus();
        while (true) {
          if ((isReady = call.isReady()) || status != null) {
            break;
          }
          if (!cond.await(remainingNanos, TimeUnit.NANOSECONDS)) {
            return false;
          }
          remainingNanos = end - System.nanoTime();
        }
        checkStatus();
        if (isReady) {
          call.sendMessage(req);
          return true;
        }
        return false;
      } finally {
        lock.unlock();
      }
    }

    @Override
    public boolean offerUninterruptibly(ReqT req, long timeout, TimeUnit unit) {
      return false;
    }

    @Override
    public void put(ReqT req) throws InterruptedException {

    }

    @Override
    public void putUninterruptibly(ReqT req) {

    }

    @Override
    public void putQueued(ReqT req) {

    }

    @Override
    public boolean offerUntilReadable(ReqT req, long timeout, TimeUnit unit)
        throws InterruptedException {
      return false;
    }

    @Override
    public boolean offerUntilReadable(ReqT req) throws InterruptedException {
      return false;
    }

    @Override
    public boolean offerUninterruptiblyUntilReadable(ReqT req) {
      return false;
    }

    @Override
    public boolean offerUninterruptiblyUntilReadable(ReqT req, long timeout, TimeUnit unit) {
      return false;
    }

    private final boolean offer(
        ReqT req, boolean interruptible, boolean checkRead, boolean checkWrite)
            throws InterruptedException {
      lock.lock();
      try {
        while (true) {
          if (status != null) {
            if (status.isOk()) {
              return false;
            } else {
              throw status.asRuntimeException(trailers);
            }
          }
          if (!checkRead && !checkWrite) {
            call.sendMessage(req);
            return true;
          } else if (checkWrite && call.isReady()) {
            call.sendMessage(req);
            return true;
          } else if (checkRead && !responses.isEmpty()) {
            return false;
          }
          if (interruptible) {
            cond.await();
          } else {
            cond.awaitUninterruptibly();
          }
        }
      } finally {
        lock.unlock();
      }
    }

    public void halfClose() {
      call.halfClose();
    }

    public void cancel(@Nullable String message, @Nullable Throwable t) {
      call.cancel(message, t);
    }

    @Override
    public boolean isComplete() {
      lock.lock();
      try {
        return status != null;
      } finally {
        lock.unlock();
      }
    }

    @Nullable
    public Status getStatus() {
      lock.lock();
      try {
        return status;
      } finally {
        lock.unlock();
      }
    }
  }
}
