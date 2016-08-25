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
import com.google.common.collect.ImmutableList;

import java.util.List;

import javax.annotation.concurrent.GuardedBy;
import javax.annotation.concurrent.ThreadSafe;

/**
 * Base implementation of {@link LoadBalancer} encapsulating all the boiler plate needed to
 * implement {@link LoadBalancer} interface.
 *
 * <p>The user is required to only provide {@link TransportPicker} instance (responsible for
 * transport selection) by extending this class and implementing {@link #createTransportPicker(List,
 * Attributes)}. Base implementation takes care of dealing with interim transports and thread
 * safety.
 *
 * <p>For reference implementations, check very simple {@link PickFirstBalancer} and a bit more
 * advanced {@link RoundRobinLoadBalancer} using custom structure for to do transport selection. If
 * more control and flexibility is required, {@link LoadBalancer} interface should be implemented
 * directly.
 */
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
   * Factory method returning {@link TransportPicker} which is responsible for transport selection.
   *
   * @param servers immutable list of servers returned from {@link NameResolver}.
   * @param initialAttributes metadata attributes returned from {@link NameResolver}.
   * @return TransportPicker object for given transport type.
   */
  protected abstract TransportPicker<TransportT> createTransportPicker(
      List<ResolvedServerInfoGroup> servers, Attributes initialAttributes);

  /**
   * Delegates transport picking duty to {@link TransportPicker} in a thread safe fashion.
   *
   * @param affinity attributes passed down to {@link TransportPicker#pickTransport(Attributes)}.
   * @return transport returned from {@link TransportPicker#pickTransport(Attributes)}.
   */
  @Override
  public TransportT pickTransport(Attributes affinity) {
    TransportPicker<TransportT> transportPickerCopy;
    // TODO(lukaszx0) address lock contention issue (https://github.com/grpc/grpc-java/issues/2121)
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

  /**
   * Handles address resolution triggered by {@link NameResolver}.
   *
   * <p>On every {@link NameResolver.Listener#onUpdate}, new {@link TransportPicker} is
   * created with a immutable list of servers returned from name resolver and metadata attributes.
   *
   * @param updatedServers list of servers returned from {@link NameResolver}.
   * @param attributes extra metadata from naming system.
   */
  @Override
  public void handleResolvedAddresses(final List<ResolvedServerInfoGroup> updatedServers,
      final Attributes attributes) {
    TransportManager.InterimTransport<TransportT> savedInterimTransport;
    final TransportPicker<TransportT> transportPickerCopy;
    synchronized (lock) {
      if (closed) {
        return;
      }
      transportPicker = createTransportPicker(ImmutableList.copyOf(updatedServers), attributes);
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

  /**
   * Handles name resolution error returned from {@link NameResolver}.
   *
   * @param error a non-OK status returned from {@link NameResolver.Listener#onError}.
   */
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

  /**
   * Shuts down load balancer and closes all remaining requests with {@link #SHUTDOWN_STATUS}.
   */
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

