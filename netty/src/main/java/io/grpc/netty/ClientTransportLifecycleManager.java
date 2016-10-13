/*
 * Copyright 2016, Google Inc. All rights reserved.
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

package io.grpc.netty;

import io.grpc.Status;
import io.grpc.internal.ManagedClientTransport;

/** Maintainer of transport lifecycle status. */
final class ClientTransportLifecycleManager {
  private final ManagedClientTransport.Listener listener;
  private boolean transportReady;
  private boolean transportShutdown;
  private int transportUsers;
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

  public void notifyShutdown(Status s) {
    if (transportShutdown) {
      return;
    }
    transportShutdown = true;
    shutdownStatus = s;
    shutdownThrowable = s.asException();
    listener.transportShutdown(s);
  }

  public void notifyNewUser() {
    transportUsers++;
    if (transportUsers == 1) {
      listener.transportInUse(true);
    }
  }

  public void notifyLostUser() {
    transportUsers--;

    if (transportUsers < 0) {
      throw new AssertionError();
    }

    if (transportUsers == 0) {
      listener.transportInUse(false);
    }
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
