/*
 * Copyright 2025 The gRPC Authors
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

import static com.google.common.base.Preconditions.checkNotNull;
import static com.google.common.base.Preconditions.checkState;

import java.util.ArrayList;
import java.util.List;

/**
 * Placeholder for an object that will be provided later.
 *
 * <p>Similar to {@link com.google.common.util.concurrent.SettableFuture}, except it cannot fail or
 * be cancelled. Most importantly, this class guarantees that {@link Listener}s run one-at-a-time
 * and in the same order that they were scheduled. This conveniently matches the expectations of
 * most listener interfaces in the io.grpc universe.
 *
 * <p>Not safe for concurrent use by multiple threads. Thread-compatible for callers that provide
 * synchronization externally.
 */
public class SimplePromise<T> {
  private T value;
  private List<Listener<T>> pendingListeners; // Allocated lazily in the hopes it's never needed.

  /**
   * Provides the promised object and runs any pending listeners.
   *
   * @throws IllegalStateException if this method has already been called
   * @throws RuntimeException if some pending listener threw when we tried to run it
   */
  public void set(T value) {
    checkNotNull(value, "value");
    checkState(this.value == null, "Already set!");
    this.value = value;
    if (pendingListeners != null) {
      for (Listener<T> listener : pendingListeners) {
        listener.notify(value);
      }
      pendingListeners = null;
    }
  }

  /**
   * Returns the promised object, under the assumption that it's already been set.
   *
   * <p>Compared to {@link #runWhenSet(Listener)}, this method may be a more efficient way to access
   * the promised value in the case where you somehow know externally that {@link #set(T)} has
   * "happened-before" this call.
   *
   * @throws IllegalStateException if {@link #set(T)} has not yet been called
   */
  public T get() {
    checkState(value != null, "Not yet set!");
    return value;
  }

  /**
   * Runs the given listener when this promise is fulfilled, or immediately if already fulfilled.
   *
   * @throws RuntimeException if already fulfilled and 'listener' threw when we tried to run it
   */
  public void runWhenSet(Listener<T> listener) {
    if (value != null) {
      listener.notify(value);
    } else {
      if (pendingListeners == null) {
        pendingListeners = new ArrayList<>();
      }
      pendingListeners.add(listener);
    }
  }

  /**
   * An object that wants to get notified when a SimplePromise has been fulfilled.
   */
  public interface Listener<T> {
    /**
     * Indicates that the associated SimplePromise has been fulfilled with the given `value`.
     */
    void notify(T value);
  }
}
