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

import java.net.SocketAddress;
import java.util.ArrayList;
import java.util.List;

/**
 * A {@link LoadBalancer} that provides no load balancing mechanism over the
 * addresses from the {@link NameResolver}.  The channel's default behavior
 * (currently pick-first) is used for all addresses found.
 */
@ExperimentalApi("https://github.com/grpc/grpc-java/issues/1771")
public final class PickFirstBalancerFactory extends LoadBalancer.Factory {

  private static final PickFirstBalancerFactory instance = new PickFirstBalancerFactory();

  private PickFirstBalancerFactory() {
  }

  public static PickFirstBalancerFactory getInstance() {
    return instance;
  }

  @Override
  public <T> LoadBalancer<T> newLoadBalancer(String serviceName, TransportManager<T> tm) {
    return new SimpleLoadBalancer<T>(tm, new PickFirstBalancerFactory.PickFirstTransportPickerFactory<T>(tm));
  }

  private static class PickFirstTransportPickerFactory<T> implements TransportPicker.Factory<T> {
    private final TransportManager<T> tm;

    public PickFirstTransportPickerFactory(TransportManager<T> tm) {
      this.tm = tm;
    }

    @Override
    public TransportPicker<T> create(List<ResolvedServerInfoGroup> updatedServers,
        Attributes attributes) {
      final EquivalentAddressGroup equivalentAddressGroup =
          resolvedServerInfoToEquivalentAddressGroup(updatedServers);
      // The simplest possible picker, just delegates to transport manager converted server list.
      return new TransportPicker<T>() {
        @Override
        public T pickTransport(Attributes affinityAttributes) {
          return tm.getTransport(equivalentAddressGroup);
        }
      };
    }

    private EquivalentAddressGroup resolvedServerInfoToEquivalentAddressGroup(
        List<ResolvedServerInfoGroup> updatedServers) {
      ArrayList<SocketAddress> newAddressList = new ArrayList<SocketAddress>();
      for (ResolvedServerInfoGroup servers : updatedServers) {
        if (servers.getResolvedServerInfoList().isEmpty()) {
          continue;
        }
        for (ResolvedServerInfo server : servers.getResolvedServerInfoList()) {
          newAddressList.add(server.getAddress());
        }
      }
      return new EquivalentAddressGroup(newAddressList);
    }
  }
}
