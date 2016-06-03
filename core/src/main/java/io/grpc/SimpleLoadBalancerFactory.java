/*
 * Copyright 2015, Google Inc. All rights reserved.
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

import io.grpc.TransportManager.InterimTransport;

import java.util.ArrayList;
import java.util.List;

import javax.annotation.concurrent.GuardedBy;

/**
 * A {@link LoadBalancer} that provides pick-first routing mechanism over the
 * {@link EquivalentAddressGroup}s from the {@link NameResolver}.
 */
// TODO(zhangkun83): Only pick-first is implemented. We need to implement round-robin.
@ExperimentalApi("https://github.com/grpc/grpc-java/issues/1771")
public final class SimpleLoadBalancerFactory extends LoadBalancer.Factory {

  private static final SimpleLoadBalancerFactory instance = new SimpleLoadBalancerFactory();

  private SimpleLoadBalancerFactory() {
  }

  public static SimpleLoadBalancerFactory getInstance() {
    return instance;
  }

  @Override
  public <T> LoadBalancer<T> newLoadBalancer(String serviceName, TransportManager<T> tm) {
    return new SimpleLoadBalancer<T>(tm);
  }

  private static class SimpleLoadBalancer<T> extends LoadBalancer<T> {
    private static final Status SHUTDOWN_STATUS =
        Status.UNAVAILABLE.augmentDescription("SimpleLoadBalancer has shut down");

    private final Object lock = new Object();

    @GuardedBy("lock")
    private EquivalentAddressGroup addresses;
    @GuardedBy("lock")
    private InterimTransport<T> interimTransport;
    @GuardedBy("lock")
    private Status nameResolutionError;
    @GuardedBy("lock")
    private boolean closed;

    private final TransportManager<T> tm;

    private SimpleLoadBalancer(TransportManager<T> tm) {
      this.tm = tm;
    }

    @Override
    public T pickTransport(Attributes affinity) {
      EquivalentAddressGroup addressesCopy;
      synchronized (lock) {
        if (closed) {
          return tm.createFailingTransport(SHUTDOWN_STATUS);
        }
        addressesCopy = addresses;
        if (addressesCopy == null) {
          if (nameResolutionError != null) {
            return tm.createFailingTransport(nameResolutionError);
          }
          if (interimTransport == null) {
            interimTransport = tm.createInterimTransport();
          }
          return interimTransport.transport();
        }
      }
      return tm.getTransport(addressesCopy);
    }

    @Override
    public void handleResolvedAddresses(
        List<EquivalentAddressGroup> updatedServers, Attributes config) {
      InterimTransport<T> savedInterimTransport;
      final EquivalentAddressGroup newAddresses;
      synchronized (lock) {
        if (closed) {
          return;
        }
        if (updatedServers.size() == 1) {
          newAddresses = updatedServers.get(0);
        } else {
          final List<ResolvedServerInfo> serverInfos = new ArrayList<ResolvedServerInfo>();
          for (final EquivalentAddressGroup group : updatedServers) {
            serverInfos.addAll(group.getResolvedServerInfos());
          }
          newAddresses = new EquivalentAddressGroup(serverInfos);
        }
        if (newAddresses.equals(addresses)) {
          return;
        }
        addresses = newAddresses;
        nameResolutionError = null;
        savedInterimTransport = interimTransport;
        interimTransport = null;
      }
      if (savedInterimTransport != null) {
        savedInterimTransport.closeWithRealTransports(new Supplier<T>() {
            @Override public T get() {
              return tm.getTransport(newAddresses);
            }
          });
      }
    }

    @Override
    public void handleNameResolutionError(Status error) {
      InterimTransport<T> savedInterimTransport;
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
      InterimTransport<T> savedInterimTransport;
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
}
