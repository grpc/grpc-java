/*
 * Copyright 2021 The gRPC Authors
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

package io.grpc.binder;

import androidx.annotation.MainThread;
import androidx.lifecycle.Lifecycle;
import androidx.lifecycle.Lifecycle.State;
import androidx.lifecycle.LifecycleEventObserver;
import androidx.lifecycle.LifecycleObserver;
import androidx.lifecycle.LifecycleOwner;
import io.grpc.ManagedChannel;
import io.grpc.Server;

/**
 * Helps work around certain quirks of {@link Lifecycle#addObserver} and {@link State#DESTROYED}.
 *
 * <p>In particular, calls to {@link Lifecycle#addObserver(LifecycleObserver)} are silently ignored
 * if the owner is already destroyed.
 */
public final class LifecycleOnDestroyHelper {

  private LifecycleOnDestroyHelper() {}

  /**
   * Arranges for {@link ManagedChannel#shutdownNow()} to be called on {@code channel} just before
   * {@code lifecycle} is destroyed, or immediately if {@code lifecycle} is already destroyed.
   *
   * <p>Must only be called on the application's main thread.
   */
  @MainThread
  public static void shutdownUponDestruction(Lifecycle lifecycle, ManagedChannel channel) {
    if (lifecycle.getCurrentState() == State.DESTROYED) {
      channel.shutdownNow();
    } else {
      lifecycle.addObserver(
          new LifecycleEventObserver() {
            @Override
            public void onStateChanged(LifecycleOwner source, Lifecycle.Event event) {
              if (event == Lifecycle.Event.ON_DESTROY) {
                source.getLifecycle().removeObserver(this);
                channel.shutdownNow();
              }
            }
          });
    }
  }

  /**
   * Arranges for {@link Server#shutdownNow()} to be called on {@code server} just before {@code
   * lifecycle} is destroyed, or immediately if {@code lifecycle} is already destroyed.
   *
   * <p>Must only be called on the application's main thread.
   */
  @MainThread
  public static void shutdownUponDestruction(Lifecycle lifecycle, Server server) {
    if (lifecycle.getCurrentState() == State.DESTROYED) {
      server.shutdownNow();
    } else {
      lifecycle.addObserver(
          new LifecycleEventObserver() {
            @Override
            public void onStateChanged(LifecycleOwner source, Lifecycle.Event event) {
              if (event == Lifecycle.Event.ON_DESTROY) {
                source.getLifecycle().removeObserver(this);
                server.shutdownNow();
              }
            }
          });
    }
  }
}
