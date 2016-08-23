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

package io.grpc;

import com.google.common.base.Supplier;

import java.util.List;

import javax.annotation.concurrent.GuardedBy;
import javax.annotation.concurrent.ThreadSafe;

@ExperimentalApi("https://github.com/grpc/grpc-java/issues/1771")
@ThreadSafe
public abstract class AbstractLoadBalancer<TransportT> extends LoadBalancer<TransportT> {
  private static final Status SHUTDOWN_STATUS =
      Status.UNAVAILABLE.augmentDescription("Load balancer has shut down");

  private final Object lock = new Object();

  @GuardedBy("lock")
  private TransportPicker<TransportT> transportPicker;
  @GuardedBy("lock")
  private TransportManager.InterimTransport<TransportT> interimTransport;
  @GuardedBy("lock")
  private Status nameResolutionError;
  @GuardedBy("lock")
  private boolean closed;

  protected final TransportManager<TransportT> tm;

  protected AbstractLoadBalancer(TransportManager<TransportT> tm) {
    this.tm = tm;
  }

  /**
   * Factory method which creates and returns {@link TransportPicker}.
   */
  protected abstract TransportPicker<TransportT> createTransportPicker(
      List<ResolvedServerInfoGroup> servers);

  @Override
  public TransportT pickTransport(Attributes affinity) {
    TransportPicker<TransportT> transportPickerCopy;
    synchronized (lock) {
      if (closed) {
        return tm.createFailingTransport(SHUTDOWN_STATUS);
      }
      if (transportPicker == null) {
        if (nameResolutionError != null) {
          return tm.createFailingTransport(nameResolutionError);
        }
        if (interimTransport == null) {
          interimTransport = tm.createInterimTransport();
        }
        return interimTransport.transport();
      }
      transportPickerCopy = transportPicker;
    }
    return transportPickerCopy.pickTransport(affinity);
  }

  @Override
  public void handleResolvedAddresses(final List<ResolvedServerInfoGroup> updatedServers,
      final Attributes attributes) {
    TransportManager.InterimTransport<TransportT> savedInterimTransport;
    final TransportPicker<TransportT> transportPickerCopy;
    synchronized (lock) {
      if (closed) {
        return;
      }
      transportPicker = createTransportPicker(updatedServers);
      transportPickerCopy = transportPicker;
      nameResolutionError = null;
      savedInterimTransport = interimTransport;
      interimTransport = null;
    }
    if (savedInterimTransport != null) {
      savedInterimTransport.closeWithRealTransports(new Supplier<TransportT>() {
        @Override
        public TransportT get() {
          return transportPickerCopy.pickTransport(attributes);
        }
      });
    }
  }

  @Override
  public void handleNameResolutionError(Status error) {
    TransportManager.InterimTransport<TransportT> savedInterimTransport;
    synchronized (lock) {
      if (closed) {
        return;
      }
      error = error.augmentDescription("Name resolution failed");
      savedInterimTransport = interimTransport;
      interimTransport = null;
      nameResolutionError = error;
    }
    if (savedInterimTransport != null) {
      savedInterimTransport.closeWithError(error);
    }
  }

  @Override
  public void shutdown() {
    TransportManager.InterimTransport<TransportT> savedInterimTransport;
    synchronized (lock) {
      if (closed) {
        return;
      }
      closed = true;
      savedInterimTransport = interimTransport;
      interimTransport = null;
    }
    if (savedInterimTransport != null) {
      savedInterimTransport.closeWithError(SHUTDOWN_STATUS);
    }
  }

}

