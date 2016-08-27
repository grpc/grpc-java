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

import java.util.List;

/**
 * Provides a simplified abstraction of transport picking used by {@link SimpleLoadBalancer}.
 *
 * @param <TransportT> transport type
 */
public interface TransportPicker<TransportT> {
  /**
   * Pick a transport that Channel will use for next RPC.
   *
   * @param affinityAttributes attributes for affinity-based routing.
   * @return selected transport.
   */
  TransportT pickTransport(Attributes affinityAttributes);

  /**
   * Factory for creating {@link TransportPicker} instances.
   *
   * @param <TransportT> transport type.
   */
  interface Factory<TransportT> {
    /**
     * Factory method returning {@link TransportPicker} which is responsible for transport
     * selection.
     *
     * @param servers immutable list of servers returned from {@link NameResolver}.
     * @param initialAttributes metadata attributes returned from {@link NameResolver}.
     * @return TransportPicker object for given transport type.
     */
    TransportPicker<TransportT> create(List<ResolvedServerInfoGroup> servers,
        Attributes initialAttributes);
  }
}
