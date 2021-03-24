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

import android.os.IBinder;
import androidx.annotation.AnyThread;
import androidx.annotation.MainThread;
import io.grpc.Status;

/** An interface for managing a {@code Binder} connection. */
interface Bindable {

  /**
   * Callbacks from this class.
   *
   * <p>Methods will be called at most once, and always on the application's main thread.
   */
  interface Observer {

    /** We're now bound to the service. Only called once, and only if the binding succeeded. */
    @MainThread
    void onBound(IBinder binder);

    /**
     * We've disconnected from (or failed to bind to) the service. This will only be called once,
     * after which no other calls will be made (but see note on threading above).
     *
     * @param reason why the connection failed or couldn't be established in the first place
     */
    @MainThread
    void onUnbound(Status reason);
  }

  /**
   * Attempt to bind with the remote service.
   *
   * <p>Calling this multiple times or after {@link #unbind()} has no effect.
   */
  @AnyThread
  void bind();

  /**
   * Unbind from the remote service if connected.
   *
   * <p>Observers will be notified with reason code {@code CANCELLED}.
   *
   * <p>Subsequent calls to {@link #bind()} (or this method) will have no effect.
   */
  @AnyThread
  void unbind();
}
