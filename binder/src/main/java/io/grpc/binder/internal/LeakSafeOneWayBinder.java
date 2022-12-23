/*
 * Copyright 2020 The gRPC Authors
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

import android.os.Binder;
import android.os.Parcel;
import io.grpc.Internal;
import java.util.logging.Level;
import java.util.logging.Logger;
import javax.annotation.Nullable;

/**
 * An extension of {@link Binder} which delegates all transactions to an internal handler, and only
 * supports one way transactions.
 *
 * <p>Since Binder objects can be anchored forever by a misbehaved remote process, any references
 * they hold can lead to significant memory leaks. To prevent that, the {@link #detach} method
 * clears the reference to the delegate handler, ensuring that only this simple class is leaked.
 * Once detached, this binder returns false from any future transactions (and pings).
 *
 * <p>Since two-way transactions block the calling thread on a remote process, this class only
 * supports one-way calls.
 */
@Internal
public final class LeakSafeOneWayBinder extends Binder {

  private static final Logger logger = Logger.getLogger(LeakSafeOneWayBinder.class.getName());

  @Internal
  public interface TransactionHandler {
    boolean handleTransaction(int code, Parcel data);
  }

  @Nullable private TransactionHandler handler;

  public LeakSafeOneWayBinder(TransactionHandler handler) {
    this.handler = handler;
  }

  public void detach() {
    handler = null;
  }

  @Override
  protected boolean onTransact(int code, Parcel parcel, Parcel reply, int flags) {
    TransactionHandler handler = this.handler;
    if (handler != null) {
      try {
        return handler.handleTransaction(code, parcel);
      } catch (RuntimeException re) {
        logger.log(Level.WARNING, "failure sending transaction " + code, re);
        return false;
      }
    } else {
      return false;
    }
  }

  @Override
  public boolean pingBinder() {
    return handler != null;
  }
}
