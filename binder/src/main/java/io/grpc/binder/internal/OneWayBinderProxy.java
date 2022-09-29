package io.grpc.binder.internal;

import android.os.Binder;
import android.os.IBinder;
import android.os.Parcel;
import android.os.RemoteException;
import io.grpc.internal.SerializingExecutor;
import java.util.concurrent.Executor;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Wraps an {@link IBinder} with a safe and uniformly asynchronous transaction API.
 *
 * <p>When the target of your bindService() call is hosted in a different process, Android supplies
 * you with an {@link IBinder} that proxies your transactions to the remote {@link
 * android.os.Binder} instance. But when the target Service is hosted in the same process, Android
 * supplies you with that local instance of {@link android.os.Binder} directly. This in-process
 * implementation of {@link IBinder} is problematic for clients that want "oneway" transaction
 * semantics because its transact() method simply invokes onTransact() on the caller's thread, even
 * when the {@link IBinder#FLAG_ONEWAY} flag is set. Even though this behavior is documented, its
 * consequences with respect to reentrancy, locking, and transaction dispatch order can be
 * surprising and dangerous.
 *
 * <p>Wrap your {@link IBinder}s with an instance of this class to ensure the following
 * out-of-process "oneway" semantics are always in effect:
 *
 * <ul>
 *   <li>transact() merely enqueues the transaction for processing. It doesn't wait for onTransact()
 *       to complete.
 *   <li>transact() may fail for programming errors or transport-layer errors that are immediately
 *       obvious on the caller's side, but never for an Exception or false return value from
 *       onTransact().
 *   <li>onTransact() runs without holding any of the locks held by the thread calling transact().
 *   <li>onTransact() calls are dispatched one at a time in the same happens-before order as the
 *       corresponding calls to transact().
 * </ul>
 *
 * <p>NB: One difference that this class can't conceal is that calls to onTransact() are serialized
 * per {@link OneWayBinderProxy} instance, not per instance of the wrapped {@link IBinder}. An
 * android.os.Binder with in-process callers could still receive concurrent calls to onTransact() on
 * different threads if callers used different {@link OneWayBinderProxy} instances or if that Binder
 * also had out-of-process callers.
 */
public abstract class OneWayBinderProxy {
  private static final Logger logger = Logger.getLogger(OneWayBinderProxy.class.getName());
  protected final IBinder delegate;

  private OneWayBinderProxy(IBinder iBinder) {
    this.delegate = iBinder;
  }

  /**
   * Returns a new instance of {@link OneWayBinderProxy} that wraps {@code iBinder}.
   *
   * @param iBinder the binder to wrap
   * @param inProcessThreadHopExecutor a non-direct Executor used to dispatch calls to onTransact(),
   *     if necessary
   * @return a new instance of {@link OneWayBinderProxy}
   */
  public static OneWayBinderProxy wrap(IBinder iBinder, Executor inProcessThreadHopExecutor) {
    return (iBinder instanceof Binder)
        ? new InProcessImpl(iBinder, inProcessThreadHopExecutor)
        : new OutOfProcessImpl(iBinder);
  }

  /**
   * Enqueues a transaction for the wrapped {@link IBinder} with guaranteed "oneway" semantics.
   *
   * <p>NB: Unlike {@link IBinder#transact}, implementations of this method take ownership of the
   * {@code data} Parcel. When this method returns, {@code data} will normally be empty, but callers
   * should still unconditionally {@link ParcelHolder#close()} it to avoid a leak in case they or
   * the implementation throws before ownership is transferred.
   *
   * @param code identifies the type of this transaction
   * @param data a non-empty container of the Parcel to be sent
   * @throws RemoteException if the transaction could not even be queued for dispatch on the server.
   *     Failures from {@link Binder#onTransact} are *never* reported this way.
   */
  public abstract void transact(int code, ParcelHolder data) throws RemoteException;

  /**
   * Returns the wrapped {@link IBinder} for the purpose of calling methods other than {@link
   * IBinder#transact(int, Parcel, Parcel, int)}.
   */
  public IBinder getDelegate() {
    return delegate;
  }

  static class OutOfProcessImpl extends OneWayBinderProxy {
    OutOfProcessImpl(IBinder iBinder) {
      super(iBinder);
    }

    @Override
    public void transact(int code, ParcelHolder data) throws RemoteException {
      if (!transactAndRecycleParcel(code, data.release())) {
        // This cannot happen (see g/android-binder/c/jM4NvS234Rw) but, just in case, let the caller
        // handle it along with all the other possible transport-layer errors.
        throw new RemoteException("BinderProxy#transact(" + code + ", FLAG_ONEWAY) returned false");
      }
    }
  }

  protected boolean transactAndRecycleParcel(int code, Parcel data) throws RemoteException {
    try {
      return delegate.transact(code, data, null, IBinder.FLAG_ONEWAY);
    } finally {
      data.recycle();
    }
  }

  static class InProcessImpl extends OneWayBinderProxy {
    private final SerializingExecutor executor;

    InProcessImpl(IBinder binder, Executor executor) {
      super(binder);
      this.executor = new SerializingExecutor(executor);
    }

    @Override
    public void transact(int code, ParcelHolder wrappedParcel) {
      // Transfer ownership, taking care to handle any RuntimeException from execute().
      Parcel parcel = wrappedParcel.get();
      executor.execute(
          () -> {
            try {
              if (!transactAndRecycleParcel(code, parcel)) {
                // onTransact() in our same process returned this. Ignore it, just like Android
                // would have if the android.os.Binder was in another process.
                logger.log(Level.FINEST, "A oneway transaction was not understood - ignoring");
              }
            } catch (Exception e) {
              // onTransact() in our same process threw this. Ignore it, just like Android would
              // have if the android.os.Binder was in another process.
              logger.log(Level.FINEST, "A oneway transaction threw - ignoring", e);
            }
          });
      wrappedParcel.release();
    }
  }
}
