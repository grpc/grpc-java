/*
 * Copyright 2016 The gRPC Authors
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

package io.grpc.netty;

import com.google.errorprone.annotations.CanIgnoreReturnValue;
import io.grpc.Status;
import io.grpc.internal.ManagedClientTransport;

/** Maintainer of transport lifecycle status. */
final class ClientTransportLifecycleManager {
  private final ManagedClientTransport.Listener listener;
  private boolean transportReady;
  private boolean transportShutdown;
  private boolean transportInUse;
  /** null iff !transportShutdown. */
  private Status shutdownStatus;
  /** null iff !transportShutdown. */
  private Throwable shutdownThrowable;
  private boolean transportTerminated;

  public ClientTransportLifecycleManager(ManagedClientTransport.Listener listener) {
    this.listener = listener;
  }

  public void notifyReady() {
    if (transportReady || transportShutdown) {
      return;
    }
    transportReady = true;
    listener.transportReady();
  }

  /**
   * Marks transport as shutdown, but does not set the error status. This must eventually be
   * followed by a call to notifyShutdown.
   */
  public void notifyGracefulShutdown(Status s) {
    if (transportShutdown) {
      return;
    }
    transportShutdown = true;
    listener.transportShutdown(s);
  }

  /** Returns {@code true} if was the first shutdown. */
  @CanIgnoreReturnValue
  public boolean notifyShutdown(Status s) {
    notifyGracefulShutdown(s);
    if (shutdownStatus != null) {
      return false;
    }
    shutdownStatus = s;
    shutdownThrowable = s.asException();
    return true;
  }

  public void notifyInUse(boolean inUse) {
    if (inUse == transportInUse) {
      return;
    }
    transportInUse = inUse;
    listener.transportInUse(inUse);
  }

  public void notifyTerminated(Status s) {
    if (transportTerminated) {
      return;
    }
    transportTerminated = true;
    notifyShutdown(s);
    listener.transportTerminated();
  }

  public Status getShutdownStatus() {
    return shutdownStatus;
  }

  public Throwable getShutdownThrowable() {
    return shutdownThrowable;
  }
}
