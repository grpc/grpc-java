/*
 * Copyright 2024 The gRPC Authors
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
package io.grpc.binder.internal;

import android.os.RemoteException;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;
import javax.annotation.Nullable;

/** A collection of {@link OneWayBinderProxy}-related test helpers. */
public final class OneWayBinderProxies {
  /**
   * A {@link OneWayBinderProxy.Decorator} that blocks calling threads while an (external) test
   * provides the actual decoration.
   */
  public static final class BlockingBinderDecorator<T extends OneWayBinderProxy>
      implements OneWayBinderProxy.Decorator {
    private final BlockingQueue<OneWayBinderProxy> requests = new LinkedBlockingQueue<>();
    private final BlockingQueue<T> results = new LinkedBlockingQueue<>();

    /**
     * Returns the next {@link OneWayBinderProxy} that needs decorating, blocking if it hasn't yet
     * been provided to {@link #decorate}.
     *
     * <p>Follow this with a call to {@link #putNextResult(OneWayBinderProxy)} to provide the result
     * of {@link #decorate} and unblock the waiting caller.
     */
    public OneWayBinderProxy takeNextRequest() throws InterruptedException {
      return requests.take();
    }

    /** Provides the next value to return from {@link #decorate}. */
    public void putNextResult(T next) throws InterruptedException {
      results.put(next);
    }

    @Override
    public OneWayBinderProxy decorate(OneWayBinderProxy in) {
      try {
        requests.put(in);
        return results.take();
      } catch (InterruptedException e) {
        Thread.currentThread().interrupt();
        throw new RuntimeException(e);
      }
    }
  }

  /** A {@link OneWayBinderProxy} decorator whose transact method can artificially throw. */
  public static final class ThrowingOneWayBinderProxy extends OneWayBinderProxy {
    private final OneWayBinderProxy wrapped;
    @Nullable private RemoteException remoteException;

    ThrowingOneWayBinderProxy(OneWayBinderProxy wrapped) {
      super(wrapped.getDelegate());
      this.wrapped = wrapped;
    }

    /**
     * Causes all future invocations of transact to throw `remoteException`.
     *
     * <p>Users are responsible for ensuring their calls "happen-before" the relevant calls to
     * {@link #transact(int, ParcelHolder)}.
     */
    public void setRemoteException(RemoteException remoteException) {
      this.remoteException = remoteException;
    }

    @Override
    public void transact(int code, ParcelHolder data) throws RemoteException {
      if (remoteException != null) {
        throw remoteException;
      }
      wrapped.transact(code, data);
    }
  }

  /**
   * A {@link OneWayBinderProxy} decorator whose transact method can be configured to silently drop.
   */
  public static final class BlackHoleOneWayBinderProxy extends OneWayBinderProxy {

    private final OneWayBinderProxy wrapped;
    private boolean dropAllTransactions;

    BlackHoleOneWayBinderProxy(OneWayBinderProxy wrapped) {
      super(wrapped.getDelegate());
      this.wrapped = wrapped;
    }

    /**
     * Causes all future invocations of transact to be silently dropped.
     *
     * <p>Users are responsible for ensuring their calls "happen-before" the relevant calls to
     * {@link #transact(int, ParcelHolder)}.
     */
    public void dropAllTransactions(boolean dropAllTransactions) {
      this.dropAllTransactions = dropAllTransactions;
    }

    @Override
    public void transact(int code, ParcelHolder data) throws RemoteException {
      if (!dropAllTransactions) {
        wrapped.transact(code, data);
      }
    }
  }

  // Cannot be instantiated.
  private OneWayBinderProxies() {}
  ;
}
